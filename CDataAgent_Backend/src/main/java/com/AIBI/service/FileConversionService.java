package com.AIBI.service;

import cn.hutool.core.io.FileUtil;
import com.AIBI.config.DuckDbConfig;
import com.AIBI.mapper.DataFileMapper;
import com.AIBI.model.entity.DataFile;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 文件转换服务：上传文件 → 类型推断 → Parquet 写入 → DuckDB 注册。
 * <p>
 * 转换流程：
 * <ol>
 *   <li>校验上传文件（xlsx/xls/csv，≤10MB）</li>
 *   <li>CSV：直接通过 DuckDB read_csv_auto → COPY TO Parquet</li>
 *   <li>XLSX/XLS：EasyExcel 流式读取 → 临时 CSV → DuckDB read_csv_auto → COPY TO Parquet</li>
 *   <li>加载 Parquet schema，采样列值</li>
 *   <li>记录元信息到 H2</li>
 * </ol>
 * <p>
 * 文件绑定策略：一个对话一个文件集合。重新上传时替换旧文件。
 */
@Slf4j
@Service
public class FileConversionService {

    @Autowired
    private DuckDbConfig duckDbConfig;

    @Autowired
    private DataFileMapper dataFileMapper;

    @Value("${data.file.storage-dir:/data/cdata-files}")
    private String storageDir;

    @Value("${data.file.max-file-size:10MB}")
    private DataSize maxFileSize;

    /** 允许的文件扩展名 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("xlsx", "xls", "csv");

    // ======================== 批量上传（外部入口） ========================

    /**
     * 批量上传文件到指定对话。
     * <p>
     * 事务性保证：先转换所有新文件 → 全部成功后删除旧文件 → 写入 H2。
     * 任何一步失败都不会丢失旧数据。
     *
     * @param files           上传的文件数组
     * @param conversationId  对话 ID
     * @param replaceIfExists 是否替换该对话已有文件
     * @return 新创建的 DataFile 列表
     */
    public List<DataFile> batchUpload(MultipartFile[] files, Long conversationId,
                                       boolean replaceIfExists) throws Exception {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("至少需要上传一个文件");
        }

        // 1. 预校验所有文件
        for (MultipartFile file : files) {
            validateFile(file);
        }

        // 2. 逐个转换（暂不写 H2）
        List<DataFile> resultList = new ArrayList<>();
        List<DataFile> newRecords = new ArrayList<>();  // 真正新文件，需 insert
        Set<Long> reusedIds = new HashSet<>();           // 复用记录 ID，跳过入库
        List<Path> tempFiles = new ArrayList<>();        // 用于失败回滚

        try {
            for (MultipartFile file : files) {
                DataFile record = convertSingle(file, conversationId, tempFiles);
                resultList.add(record);
                if (record.getId() == null) {
                    newRecords.add(record);
                } else {
                    reusedIds.add(record.getId());
                }
            }

            // 3. 全部成功后，如果要求替换则删除旧文件（排除正在复用的）
            if (replaceIfExists) {
                deleteConversationFilesExcluding(conversationId, reusedIds);
            }

            // 4. 只写入新记录
            for (DataFile df : newRecords) {
                dataFileMapper.insert(df);
                log.info("文件已入库: {} (view={}, {}行)", df.getOriginalFilename(),
                        df.getViewName(), df.getRowCount());
            }

            return resultList;

        } catch (Exception e) {
            // 回滚：只删除新生成的 Parquet 文件
            for (DataFile df : newRecords) {
                try {
                    Files.deleteIfExists(Path.of(df.getStoragePath()));
                } catch (IOException ignored) {}
            }
            // 删除临时 CSV 文件
            for (Path tmp : tempFiles) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {}
            }
            throw e;
        }
    }

    // ======================== 单文件转换 ========================

    private DataFile convertSingle(MultipartFile file, Long conversationId,
                                    List<Path> tempFiles) throws Exception {
        String originalFilename = file.getOriginalFilename();
        String ext = FileUtil.getSuffix(originalFilename).toLowerCase();

        // 计算文件内容哈希（SHA256(filename + fileBytes)）
        String contentHash = computeContentHash(originalFilename, file.getBytes());

        // 查询是否已有相同文件（去重）
        QueryWrapper<DataFile> qw = new QueryWrapper<>();
        qw.eq("contentHash", contentHash).eq("status", "READY").last("LIMIT 1");
        DataFile existing = dataFileMapper.selectOne(qw);

        if (existing != null && Files.exists(Path.of(existing.getStoragePath()))) {
            // 复用已有记录，不保存新记录，跳过转换
            log.info("文件复用（跳过入库）: {} → id={}, Parquet={} ({}行)",
                    originalFilename, existing.getId(), existing.getStoragePath(), existing.getRowCount());
            return existing;
        }

        // 准备路径（无 userId 层级）
        String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Path targetDir = Paths.get(storageDir, dateDir);
        Files.createDirectories(targetDir);

        String fileId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Path sourcePath = targetDir.resolve(fileId + "." + ext);
        Path parquetPath = targetDir.resolve(fileId + ".parquet");

        // 保存上传文件到磁盘
        file.transferTo(sourcePath.toFile());
        tempFiles.add(sourcePath);

        String viewName = "data_" + fileId;

        // 转换：xlsx/xls → CSV（EasyExcel），CSV → 直接 DuckDB
        Path csvPath = null;
        if ("csv".equals(ext)) {
            csvPath = sourcePath; // CSV 直接用原文件
        } else {
            // XLSX/XLS → EasyExcel 流式读取 → 临时 CSV
            csvPath = targetDir.resolve(fileId + "_temp.csv");
            convertXlsxToCsv(sourcePath.toString(), csvPath);
            tempFiles.add(csvPath);
        }

        // DuckDB：CSV → Parquet
        try {
            convertCsvToParquet(csvPath.toString(), parquetPath.toString(), viewName);
        } finally {
            // 清理临时文件
            Files.deleteIfExists(sourcePath);
            if (!"csv".equals(ext)) {
                Files.deleteIfExists(csvPath);
            }
        }

        // 加载 schema
        SchemaInfo schema = loadSchema(parquetPath.toString(), viewName);

        // 构建实体
        DataFile dataFile = new DataFile();
        dataFile.setOriginalFilename(originalFilename);
        dataFile.setStoragePath(parquetPath.toString());
        dataFile.setFileSize(file.getSize());
        dataFile.setRowCount(schema.rowCount);
        dataFile.setColumnMeta(JSON.toJSONString(schema.columns.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name);
            m.put("type", c.type);
            m.put("samples", c.samples != null ? c.samples : Collections.emptyList());
            return m;
        }).collect(Collectors.toList())));
        dataFile.setViewName(viewName);
        dataFile.setStatus("READY");
        dataFile.setConversationId(conversationId);
        dataFile.setContentHash(contentHash);
        dataFile.setCreateTime(java.time.LocalDateTime.now());
        dataFile.setUpdateTime(java.time.LocalDateTime.now());

        log.info("文件转换完成: {} → {} ({}行, {}列)", originalFilename, viewName,
                schema.rowCount, schema.columns.size());
        return dataFile;
    }

    // ======================== XLSX → CSV（EasyExcel 流式） ========================

    /**
     * 使用 EasyExcel 流式读取 xlsx/xls，写入临时 CSV。
     */
    private void convertXlsxToCsv(String xlsxPath, Path csvPath) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(csvPath.toFile()), StandardCharsets.UTF_8))) {

            EasyExcel.read(xlsxPath, new AnalysisEventListener<Map<Integer, String>>() {

                private int columnCount = 0;

                @Override
                public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
                    try {
                        // 写入 CSV 表头
                        List<String> headers = new ArrayList<>();
                        for (int i = 0; i < headMap.size(); i++) {
                            String header = headMap.get(i);
                            headers.add(header != null ? escapeCsv(header) : "Column" + i);
                        }
                        this.columnCount = headers.size();
                        writer.write(String.join(",", headers));
                        writer.newLine();
                    } catch (IOException e) {
                        throw new RuntimeException("写 CSV 表头失败", e);
                    }
                }

                @Override
                public void invoke(Map<Integer, String> row, AnalysisContext context) {
                    try {
                        List<String> values = new ArrayList<>();
                        for (int i = 0; i < columnCount; i++) {
                            String val = row.get(i);
                            values.add(val != null ? escapeCsv(val) : "");
                        }
                        writer.write(String.join(",", values));
                        writer.newLine();
                    } catch (IOException e) {
                        throw new RuntimeException("写 CSV 数据行失败", e);
                    }
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    log.debug("EasyExcel 读取完成: {} 行", context.readRowHolder().getRowIndex());
                }
            }).sheet().doRead();
        }

        log.info("XLSX → CSV 完成: {} → {}", xlsxPath, csvPath);
    }

    /**
     * CSV 字段转义（含引号、逗号、换行）。
     */
    private static String escapeCsv(String value) {
        if (value.contains("\"") || value.contains(",") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ======================== CSV → Parquet（DuckDB） ========================

    /**
     * 使用 DuckDB read_csv_auto 读取 CSV，COPY 到 Parquet。
     * <p>
     * 编码策略：先尝试 UTF-8 → 失败则尝试 GBK → 仍失败则抛出异常。
     */
    private void convertCsvToParquet(String csvPath, String parquetPath, String viewName) throws Exception {
        try (Connection conn = duckDbConfig.createMemoryConnection();
             Statement stmt = conn.createStatement()) {

            // 先尝试读 CSV，编码自动探测
            String csvReadResult = tryReadCsvAuto(stmt, csvPath, viewName);
            if (csvReadResult != null) {
                throw new Exception("无法读取 CSV 文件: " + csvReadResult);
            }

            // 写入 Parquet
            stmt.execute("COPY (SELECT * FROM " + viewName + ") TO '" +
                    parquetPath.replace("'", "''") + "' (FORMAT PARQUET, COMPRESSION SNAPPY)");
        }
    }

    /**
     * 尝试用 read_csv_auto 读取 CSV，自动探测编码。
     *
     * @return null 表示成功，否则返回错误描述
     */
    private String tryReadCsvAuto(Statement stmt, String csvPath, String viewName) {
        // 尝试 1：UTF-8
        try {
            stmt.execute("CREATE OR REPLACE VIEW " + viewName +
                    " AS SELECT * FROM read_csv_auto('" + csvPath.replace("'", "''") + "')");
            // 验证能否读取
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + viewName);
            rs.next();
            int cnt = rs.getInt(1);
            if (cnt > 0) return null; // 成功
        } catch (Exception e) {
            log.debug("UTF-8 编码读取失败，尝试 GBK: {}", e.getMessage());
        }

        // 尝试 2：GBK
        try {
            stmt.execute("CREATE OR REPLACE VIEW " + viewName +
                    " AS SELECT * FROM read_csv_auto('" + csvPath.replace("'", "''") +
                    "', encoding='gbk')");
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + viewName);
            rs.next();
            return null; // 成功
        } catch (Exception e) {
            log.debug("GBK 编码读取也失败: {}", e.getMessage());
        }

        return "CSV 文件编码无法识别（已尝试 UTF-8 和 GBK）";
    }

    // ======================== Schema 加载 ========================

    /**
     * 加载 Parquet 文件的 schema 信息。
     */
    private SchemaInfo loadSchema(String parquetPath, String viewName) throws Exception {
        try (Connection conn = duckDbConfig.createConnection(parquetPath, viewName);
             Statement stmt = conn.createStatement()) {

            // 获取列信息
            List<ColumnInfo> columns = new ArrayList<>();
            ResultSet rs = stmt.executeQuery("DESCRIBE " + viewName);
            while (rs.next()) {
                ColumnInfo col = new ColumnInfo();
                col.name = rs.getString("column_name");
                col.type = rs.getString("column_type");
                columns.add(col);
            }

            // 获取行数
            rs = stmt.executeQuery("SELECT COUNT(*) FROM " + viewName);
            rs.next();
            int rowCount = rs.getInt(1);

            // 每列采样 3 个值
            for (ColumnInfo col : columns) {
                try {
                    ResultSet sampleRs = stmt.executeQuery(
                            "SELECT \"" + col.name + "\" FROM " + viewName + " LIMIT 3");
                    List<String> samples = new ArrayList<>();
                    while (sampleRs.next()) {
                        String v = sampleRs.getString(1);
                        if (v != null) samples.add(v);
                    }
                    col.samples = samples;
                } catch (Exception e) {
                    col.samples = Collections.emptyList();
                }
            }

            SchemaInfo schema = new SchemaInfo();
            schema.columns = columns;
            schema.rowCount = rowCount;
            return schema;
        }
    }

    /**
     * 获取文件 schema（供 Agent 工具调用）。
     * <p>
     * 优先从 DataFile.columnMeta 重建（转换时已存储），避免重复的 DuckDB DESCRIBE 调用。
     * columnMeta 为空/null 时回退到 DuckDB 实时查询。
     */
    public SchemaInfo getSchema(String viewName, String parquetPath) throws Exception {
        // Path A: 从 DataFile.columnMeta 重建（已持久化的 schema 信息）
        QueryWrapper<DataFile> qw = new QueryWrapper<>();
        qw.eq("viewName", viewName);
        DataFile dataFile = dataFileMapper.selectOne(qw);
        if (dataFile != null && dataFile.getColumnMeta() != null && !dataFile.getColumnMeta().isBlank()) {
            JSONArray colsJson = JSON.parseArray(dataFile.getColumnMeta());
            if (colsJson != null && !colsJson.isEmpty()) {
                SchemaInfo schema = new SchemaInfo();
                List<ColumnInfo> columns = new ArrayList<>();
                for (int i = 0; i < colsJson.size(); i++) {
                    JSONObject colJson = colsJson.getJSONObject(i);
                    ColumnInfo col = new ColumnInfo();
                    col.name = colJson.getString("name");
                    col.type = colJson.getString("type");
                    JSONArray samplesJson = colJson.getJSONArray("samples");
                    col.samples = samplesJson != null
                            ? samplesJson.stream().map(Object::toString).collect(Collectors.toList())
                            : Collections.emptyList();
                    columns.add(col);
                }
                schema.columns = columns;
                schema.rowCount = dataFile.getRowCount() != null ? dataFile.getRowCount() : 0;
                log.debug("getSchema: 从 columnMeta 重建, viewName={}, {}列, {}行",
                        viewName, columns.size(), schema.rowCount);
                return schema;
            }
        }
        // Path B: columnMeta 不可用时回退到 DuckDB 实时查询
        log.debug("getSchema: columnMeta 不可用, 回退到 DuckDB, viewName={}", viewName);
        return loadSchema(parquetPath, viewName);
    }

    // ======================== 文件清理 ========================

    /**
     * 删除对话关联的所有文件（物理删除 Parquet + 逻辑删除 H2 记录）。
     */
    public void deleteConversationFiles(Long conversationId) {
        deleteConversationFilesExcluding(conversationId, Collections.emptySet());
    }

    /**
     * 删除对话关联的文件，排除指定 ID 的记录（供 replaceIfExists + 文件复用时使用）。
     */
    private void deleteConversationFilesExcluding(Long conversationId, Set<Long> excludeIds) {
        if (conversationId == null) return;

        List<DataFile> files = dataFileMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DataFile>()
                        .eq("conversationId", conversationId));

        for (DataFile df : files) {
            if (excludeIds.contains(df.getId())) {
                log.debug("跳过删除（正在复用）: id={}, {}", df.getId(), df.getOriginalFilename());
                continue;
            }
            try {
                Files.deleteIfExists(Path.of(df.getStoragePath()));
            } catch (IOException e) {
                log.warn("删除 Parquet 文件失败: {}", df.getStoragePath(), e);
            }
            dataFileMapper.deleteById(df.getId());
        }

        if (!files.isEmpty()) {
            log.info("已清理文件：{}个文件（排除{}个复用）",
                    files.size(), excludeIds.size());
        }
    }

    // ======================== 内容哈希（去重） ========================

    /**
     * 计算文件内容哈希：SHA256(originalFilename + fileBytes)，用于文件去重。
     */
    private String computeContentHash(String originalFilename, byte[] fileBytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(originalFilename.getBytes(StandardCharsets.UTF_8));
            md.update(fileBytes);
            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("计算文件哈希失败", e);
        }
    }

    // ======================== 校验 ========================

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        String ext = FileUtil.getSuffix(file.getOriginalFilename()).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型: " + ext + "，仅支持 xlsx/xls/csv");
        }
        if (file.getSize() > maxFileSize.toBytes()) {
            throw new IllegalArgumentException("文件大小超过 " + maxFileSize.toMegabytes() + "MB 限制");
        }
    }

    // ======================== 内部类 ========================

    /** 列信息 */
    public static class ColumnInfo {
        public String name;
        public String type;
        public List<String> samples = Collections.emptyList();
    }

    /** Schema 信息 */
    public static class SchemaInfo {
        public List<ColumnInfo> columns;
        public int rowCount;
    }
}
