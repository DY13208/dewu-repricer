<template>
  <div class="page">
    <div class="page-head"><div><h2>规则配置</h2></div><el-button type="primary" @click="edit({ mode: 'BELOW', offset_type: 'AMOUNT', offset_value: 1, fee_rate: 0, fee_min: 0, fulfillment_fee: 0, max_change_ratio: 0.15, price_step: 100, price_rule: 'DEWU_900', enabled: true })">新建策略</el-button></div>
    <div class="tile">
    <el-table :data="rows">
      <el-table-column prop="id" label="ID" width="60" /><el-table-column prop="name" label="名称" />
      <el-table-column label="规则"><template #default="{ row }">{{ modeText[row.mode] }} {{ row.offset_type === 'PERCENT' ? row.offset_value + '%' : fen2yuan(row.offset_value) + '元' }}</template></el-table-column>
      <el-table-column label="最低利润"><template #default="{ row }">{{ fen2yuan(row.min_profit) }}</template></el-table-column>
      <el-table-column label="售价区间"><template #default="{ row }">{{ fen2yuan(row.min_price) }} ~ {{ fen2yuan(row.max_price) }}</template></el-table-column>
      <el-table-column prop="fee_rate" label="费率" /><el-table-column prop="max_change_ratio" label="熔断比例" />
      <el-table-column label="操作"><template #default="{ row }"><el-button size="small" @click="edit(row)">编辑</el-button></template></el-table-column>
    </el-table>
    </div>
    <div class="tile">
    <div class="tile-head">策略分配</div>
    <el-form inline class="toolbar">
      <el-form-item label="策略"><el-select v-model="a.strategyId" style="width:140px"><el-option v-for="s in rows" :key="s.id" :value="s.id" :label="s.name" /></el-select></el-form-item>
      <el-form-item label="店铺"><el-select v-model="a.shopId" style="width:140px"><el-option v-for="s in shops" :key="s.id" :value="s.id" :label="s.name" /></el-select></el-form-item>
      <el-form-item label="范围"><el-select v-model="a.scope" style="width:120px"><el-option value="SHOP" label="整店" /><el-option value="PRODUCT" label="商品" /><el-option value="LISTING" label="单个出价" /></el-select></el-form-item>
      <el-form-item label="目标ID" v-if="a.scope !== 'SHOP'"><el-input v-model="a.targetId" style="width:100px" /></el-form-item>
      <el-button @click="assign">分配</el-button>
    </el-form>
    <el-table :data="assigns"><el-table-column prop="strategy_name" label="策略" /><el-table-column prop="shop_id" label="店铺" /><el-table-column prop="scope" label="范围" /><el-table-column prop="target_id" label="目标" /></el-table>
    </div>
    <el-dialog v-model="show" title="策略">
      <el-form :model="f" label-width="130px">
        <el-form-item label="名称"><el-input v-model="f.name" /></el-form-item>
        <el-form-item label="模式"><el-radio-group v-model="f.mode"><el-radio value="BELOW">低于</el-radio><el-radio value="MATCH">持平</el-radio><el-radio value="ABOVE">高于</el-radio></el-radio-group></el-form-item>
        <el-form-item label="偏移方式"><el-radio-group v-model="f.offset_type"><el-radio value="AMOUNT">固定金额(分)</el-radio><el-radio value="PERCENT">百分比</el-radio></el-radio-group></el-form-item>
        <el-form-item label="偏移值"><el-input-number v-model="f.offset_value" :min="0" /></el-form-item>
        <el-form-item label="最低利润(分)"><el-input-number v-model="f.min_profit" /></el-form-item>
        <el-form-item label="最低售价(分)"><el-input-number v-model="f.min_price" /></el-form-item>
        <el-form-item label="最高售价(分)"><el-input-number v-model="f.max_price" /></el-form-item>
        <el-form-item label="平台费率(0-1)"><el-input-number v-model="f.fee_rate" :step="0.01" :min="0" :max="0.99" /></el-form-item>
        <el-form-item label="手续费下限/上限(分)"><el-input-number v-model="f.fee_min" /> / <el-input-number v-model="f.fee_max" /></el-form-item>
        <el-form-item label="履约费用(分)"><el-input-number v-model="f.fulfillment_fee" /></el-form-item>
        <el-form-item label="单次变化熔断比例"><el-input-number v-model="f.max_change_ratio" :step="0.05" :min="0" /></el-form-item>
        <el-form-item label="价格步长(分)"><el-input-number v-model="f.price_step" :min="1" /></el-form-item>
        <el-form-item label="平台价格规则"><el-select v-model="f.price_rule"><el-option value="NONE" label="无" /><el-option value="DEWU_900" label="得物：价格(分)以 900 结尾" /></el-select></el-form-item>
        <el-form-item label="启用"><el-switch v-model="f.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import http, { fen2yuan } from '../api'
const modeText = { BELOW: '低于', MATCH: '持平', ABOVE: '高于' }
const rows = ref([]); const shops = ref([]); const assigns = ref([]); const show = ref(false); const f = ref({}); const a = ref({ scope: 'SHOP' })
const load = async () => { rows.value = await http.get('/strategies'); assigns.value = await http.get('/strategy-assignments'); shops.value = await http.get('/shops') }
const edit = r => { f.value = { ...r }; show.value = true }
const save = async () => { await http.post('/strategies', f.value); show.value = false; load() }
const assign = async () => { await http.post('/strategy-assignments', { ...a.value, targetId: a.value.scope === 'SHOP' ? null : Number(a.value.targetId) }); load() }
onMounted(load)
</script>
