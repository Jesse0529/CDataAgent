import { createApp } from 'vue'
import App from './App.vue'
import router from './router'

// Taste Soft global styles
import './styles/tokens.css'
import './styles/base.css'

const app = createApp(App)
app.use(router)
app.mount('#app')
