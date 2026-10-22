<template>
  <el-table :data="rows">
    <el-table-column prop="created_at" label="时间" /><el-table-column prop="shop_name" label="店铺" /><el-table-column prop="listing_id" label="出价" />
    <el-table-column label="原价"><template #default="{ row }">{{ fen2yuan(row.old_price) }}</template></el-table-column>
    <el-table-column label="新价"><template #default="{ row }">{{ fen2yuan(row.new_price) }}</template></el-table-column>
    <el-table-column label="回查价"><template #default="{ row }">{{ fen2yuan(row.verified_price) }}</template></el-table-column>
    <el-table-column label="竞争价"><template #default="{ row }">{{ fen2yuan(row.competitor_price) }}</template></el-table-column>
    <el-table-column prop="reason" label="原因" />
  </el-table>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import http, { fen2yuan } from '../api'
const rows = ref([]); onMounted(async () => (rows.value = await http.get('/history')))
</script>
