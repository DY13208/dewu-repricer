<template>
  <div>
    <el-button type="primary" @click="show = true">绑定店铺</el-button>
    <el-table :data="rows" style="margin-top:12px">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="店铺" />
      <el-table-column prop="group_name" label="分组" />
      <el-table-column prop="owner_entity" label="经营主体" />
      <el-table-column prop="adapter_type" label="适配器" />
      <el-table-column label="授权"><template #default="{ row }"><el-tag :type="row.auth_status === 'VALID' ? 'success' : 'danger'">{{ row.auth_status || '未授权' }}</el-tag></template></el-table-column>
      <el-table-column prop="listing_count" label="出价数" />
      <el-table-column label="跟价"><template #default="{ row }"><el-switch :model-value="row.repricing_enabled" @change="v => toggle(row, v)" /></template></el-table-column>
      <el-table-column label="操作" width="220"><template #default="{ row }">
        <el-button size="small" @click="auth(row)">授权</el-button><el-button size="small" @click="sync(row)">同步商品</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="show" title="绑定店铺">
      <el-form :model="f" label-width="110px">
        <el-form-item label="店铺名称"><el-input v-model="f.name" /></el-form-item>
        <el-form-item label="适配器"><el-select v-model="f.adapterType"><el-option value="MOCK" label="MOCK（模拟）" /><el-option value="DEWU_OPEN" label="得物开放平台（未接入）" /><el-option value="THIRD_PARTY" label="第三方授权（未接入）" /></el-select></el-form-item>
        <el-form-item label="平台店铺ID"><el-input v-model="f.externalShopId" /></el-form-item>
        <el-form-item label="经营主体"><el-input v-model="f.ownerEntity" placeholder="同主体店铺之间不互相压价" /></el-form-item>
        <el-form-item label="分组"><el-input v-model="f.groupName" /></el-form-item>
        <el-form-item label="扫描间隔(秒)"><el-input-number v-model="f.scanIntervalSeconds" :min="60" /></el-form-item>
        <el-form-item label="QPS 上限"><el-input-number v-model="f.qpsLimit" :min="0.1" :step="0.5" /></el-form-item>
      </el-form>
      <template #footer><el-button type="primary" @click="create">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api'
const rows = ref([]); const show = ref(false)
const f = ref({ adapterType: 'MOCK', scanIntervalSeconds: 600, qpsLimit: 1 })
const load = async () => (rows.value = await http.get('/shops'))
const create = async () => { await http.post('/shops', f.value); show.value = false; load() }
const auth = async r => { const x = await http.post(`/shops/${r.id}/authorize`, {}); ElMessage.info(x.message); load() }
const sync = async r => { const x = await http.post(`/shops/${r.id}/sync`); ElMessage.success(`同步商品 ${x.products}，出价 ${x.listings}`); load() }
const toggle = async (r, v) => { await http.post(`/shops/${r.id}/repricing?enabled=${v}`); load() }
onMounted(load)
</script>
