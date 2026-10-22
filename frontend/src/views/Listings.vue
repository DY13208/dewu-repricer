<template>
  <div>
    <el-form inline>
      <el-form-item label="店铺"><el-select v-model="q.shopId" clearable style="width:160px"><el-option v-for="s in shops" :key="s.id" :value="s.id" :label="s.name" /></el-select></el-form-item>
      <el-form-item label="关键词"><el-input v-model="q.keyword" placeholder="标题/货号/SKU" clearable /></el-form-item>
      <el-form-item label="是否排除"><el-select v-model="q.excluded" clearable style="width:100px"><el-option :value="true" label="是" /><el-option :value="false" label="否" /></el-select></el-form-item>
      <el-button type="primary" @click="load">查询</el-button>
      <el-upload :show-file-list="false" action="/api/costs/import" :on-success="r => ElMessage.success(`导入 ${r.imported} 条，错误 ${r.errors.length}`)" style="display:inline-block;margin-left:12px"><el-button>导入成本 Excel</el-button></el-upload>
    </el-form>
    <div style="margin-bottom:8px">
      已选 {{ sel.length }} 项：
      <el-button size="small" @click="batch({ excluded: true })">设为不参与</el-button>
      <el-button size="small" @click="batch({ excluded: false })">恢复参与</el-button>
      <el-button size="small" @click="setProfit">设置利润底线</el-button>
      <el-button size="small" @click="batch({ clearAnomaly: true })">清除异常标记</el-button>
      <el-button size="small" type="primary" @click="reprice(true)">模拟调价</el-button>
      <el-button size="small" type="danger" @click="reprice(false)">提交调价</el-button>
    </div>
    <el-table :data="rows" @selection-change="v => (sel = v)" border>
      <el-table-column type="selection" />
      <el-table-column prop="shop_name" label="店铺" />
      <el-table-column prop="title" label="商品" min-width="160" />
      <el-table-column prop="article_number" label="货号" />
      <el-table-column prop="sku_id" label="SKU" />
      <el-table-column prop="spec" label="规格" />
      <el-table-column label="当前价"><template #default="{ row }">{{ fen2yuan(row.current_price) }}</template></el-table-column>
      <el-table-column label="竞争价"><template #default="{ row }">{{ fen2yuan(row.competitor_price) }}</template></el-table-column>
      <el-table-column label="成本"><template #default="{ row }">{{ fen2yuan(row.cost) }}</template></el-table-column>
      <el-table-column label="利润底线"><template #default="{ row }">{{ fen2yuan(row.min_profit) }}</template></el-table-column>
      <el-table-column prop="stock" label="库存" width="60" />
      <el-table-column label="参与"><template #default="{ row }">{{ row.repricing_excluded ? '否' : '是' }}</template></el-table-column>
      <el-table-column prop="anomaly_flag" label="异常" min-width="140" />
    </el-table>
    <el-pagination layout="total, prev, pager, next" :total="total" v-model:current-page="q.page" :page-size="q.size" @current-change="load" />
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { fen2yuan, yuan2fen } from '../api'
const props = defineProps({ anomalyOnly: Boolean })
const q = ref({ page: 1, size: 20 }); const rows = ref([]); const total = ref(0); const sel = ref([]); const shops = ref([])
const load = async () => { const r = await http.get('/listings', { params: { ...q.value, anomaly: props.anomalyOnly || undefined } }); rows.value = r.items; total.value = r.total }
const ids = () => sel.value.map(r => r.id)
const batch = async b => { await http.post('/listings/batch', { ids: ids(), ...b }); load() }
const setProfit = () => ElMessageBox.prompt('利润底线（元）').then(({ value }) => batch({ minProfit: yuan2fen(value) }))
const reprice = async dryRun => {
  if (!sel.value.length) return ElMessage.warning('请先选择')
  if (!dryRun) await ElMessageBox.confirm('将对所选出价提交真实改价（受全局 DRY-RUN 与紧急停止约束），确认？', '确认', { type: 'warning' })
  const r = await http.post('/tasks', { listingIds: ids(), dryRun, priority: 8 }); ElMessage.success('已创建任务 #' + r.taskId)
}
onMounted(async () => { shops.value = await http.get('/shops'); load() })
</script>
