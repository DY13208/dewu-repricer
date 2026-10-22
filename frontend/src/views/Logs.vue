<template>
  <div class="page"><div class="page-head"><div><h2>接口与审计日志</h2></div></div><div class="tile">
  <el-tabs>
    <el-tab-pane label="接口调用"><el-table :data="api"><el-table-column prop="created_at" label="时间" /><el-table-column prop="shop_id" label="店铺" /><el-table-column prop="adapter" label="适配器" /><el-table-column prop="operation" label="操作" /><el-table-column prop="success" label="成功" /><el-table-column prop="latency_ms" label="耗时ms" /><el-table-column prop="error_msg" label="错误" /></el-table></el-tab-pane>
    <el-tab-pane label="审计日志"><el-table :data="audit"><el-table-column prop="created_at" label="时间" /><el-table-column prop="actor" label="操作人" /><el-table-column prop="action" label="动作" /><el-table-column prop="shop_id" label="店铺" /><el-table-column prop="target_id" label="对象" /><el-table-column prop="detail" label="详情" /></el-table></el-tab-pane>
  </el-tabs>
  </div></div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import http from '../api'
const api = ref([]); const audit = ref([])
onMounted(async () => { api.value = await http.get('/api-logs'); audit.value = await http.get('/audit-logs') })
</script>
