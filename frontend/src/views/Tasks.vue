<template>
  <div>
    <el-form inline>
      <el-form-item label="整店调价"><el-select v-model="shopId" style="width:160px"><el-option v-for="s in shops" :key="s.id" :value="s.id" :label="s.name" /></el-select></el-form-item>
      <el-checkbox v-model="dryRun">仅模拟</el-checkbox>
      <el-button type="primary" style="margin-left:12px" @click="run">创建任务</el-button>
      <el-button @click="load">刷新</el-button>
    </el-form>
    <el-table :data="rows" @row-click="open">
      <el-table-column prop="id" label="ID" width="70" /><el-table-column prop="task_type" label="类型" /><el-table-column prop="shop_id" label="店铺" />
      <el-table-column prop="priority" label="优先级" /><el-table-column prop="status" label="状态" />
      <el-table-column label="模拟"><template #default="{ row }">{{ row.dry_run ? '是' : '否' }}</template></el-table-column>
      <el-table-column label="进度"><template #default="{ row }">{{ row.succeeded }}/{{ row.failed }}/{{ row.skipped }}/{{ row.total }}</template></el-table-column>
      <el-table-column prop="created_at" label="创建时间" />
      <el-table-column label="操作"><template #default="{ row }"><el-button size="small" @click.stop="cancel(row)">取消</el-button></template></el-table-column>
    </el-table>
    <el-drawer v-model="drawer" size="60%" title="任务明细">
      <el-table :data="items"><el-table-column prop="listing_id" label="出价" /><el-table-column prop="status" label="状态" />
        <el-table-column label="原价"><template #default="{ row }">{{ fen2yuan(row.old_price) }}</template></el-table-column>
        <el-table-column label="目标价"><template #default="{ row }">{{ fen2yuan(row.target_price) }}</template></el-table-column>
        <el-table-column label="竞争价"><template #default="{ row }">{{ fen2yuan(row.competitor_price) }}</template></el-table-column>
        <el-table-column prop="decision_reason" label="原因" min-width="160" /><el-table-column prop="attempts" label="次数" /><el-table-column prop="last_error" label="错误" /></el-table>
    </el-drawer>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import http, { fen2yuan } from '../api'
const rows = ref([]); const shops = ref([]); const shopId = ref(); const dryRun = ref(true); const drawer = ref(false); const items = ref([])
const load = async () => { rows.value = await http.get('/tasks'); shops.value = await http.get('/shops') }
const run = async () => { await http.post('/tasks', { shopId: shopId.value, dryRun: dryRun.value }); load() }
const open = async r => { items.value = await http.get(`/tasks/${r.id}/items`); drawer.value = true }
const cancel = async r => { await http.post(`/tasks/${r.id}/cancel`); load() }
onMounted(load)
</script>
