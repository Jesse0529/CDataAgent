<template>
  <div class="block-table">
    <div v-if="block.title" class="block-table__title">{{ block.title }}</div>
    <div class="block-table__scroll">
      <table>
        <thead><tr><th v-for="h in block.headers" :key="h">{{ h }}</th></tr></thead>
        <tbody>
          <tr v-for="(row, ri) in block.rows" :key="ri">
            <td v-for="h in block.headers" :key="h">{{ row[h] ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="block.totalRows > block.rows.length" class="table-hint">
      显示前 {{ block.rows.length }} / {{ block.totalRows }} 行
    </p>
  </div>
</template>

<script setup lang="ts">
import type { DataTableBlock } from '@/services/types'

defineProps<{ block: DataTableBlock }>()
</script>

<style scoped>
.block-table {
  margin: 14px 0;
  overflow: hidden;
  border: 1px solid var(--border-soft);
  border-radius: 14px;
  background: var(--surface);
  box-shadow: 0 8px 24px rgb(0 0 0 / 4%);
}

.block-table__title {
  padding: 12px 14px 8px;
  color: var(--fg);
  font-size: 15px;
  font-weight: 600;
}

.block-table__scroll { overflow-x: auto; }

table {
  width: 100%;
  min-width: max-content;
  border-collapse: separate;
  border-spacing: 0;
  color: var(--fg);
  font-size: 14px;
}

th, td {
  min-width: 104px;
  padding: 10px 14px;
  text-align: left;
  vertical-align: middle;
  border-bottom: 1px solid var(--border-soft);
}

th {
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--accent-glow-soft);
  color: var(--accent);
  font-size: 13px;
  font-weight: 650;
  letter-spacing: 0.02em;
  white-space: nowrap;
}

tbody tr { transition: background-color 160ms ease; }
tbody tr:nth-child(even) { background: var(--surface-raised); }
tbody tr:hover { background: var(--accent-glow-soft); }
tbody tr:last-child td { border-bottom: 0; }

td { max-width: 320px; overflow-wrap: anywhere; }

.table-hint {
  margin: 0;
  padding: 8px 14px;
  border-top: 1px solid var(--border-soft);
  color: var(--muted);
  font-size: 12px;
}
</style>
