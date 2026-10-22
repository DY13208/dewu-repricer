<template>
  <div class="page">
    <div class="page-head"><div><h2>经营总览</h2></div></div>
    <div class="stats">
      <div class="tile stat" v-for="(label, k) in cards" :key="k"><span class="label">{{ label }}</span><span class="value">{{ d[k] ?? '-' }}</span></div>
    </div>
    <div class="tile">
      <div class="tile-head">全局控制</div>
      <div class="tile-body">
      <p style="margin-top:0">当前状态：<el-tag v-for="s in d.switches" :key="s.name" style="margin-right:8px">{{ s.name === 'GLOBAL_REPRICING' ? '全局跟价' : '紧急停止' }}: {{ s.value }}</el-tag></p>
      <el-button type="success" @click="ctl('resume')">恢复跟价</el-button>
      <el-button type="warning" @click="ctl('pause')">暂停跟价</el-button>
      <el-button type="danger" @click="confirmStop">紧急停止</el-button>
      <el-button @click="ctl('release-stop')">解除紧急停止</el-button>
      </div>
    </div>
    <div class="tile"><div class="tile-head">授权告警</div>
      <el-table :data="d.authAlerts || []" empty-text="无授权异常"><el-table-column prop="shop_id" label="店铺ID" /><el-table-column prop="status" label="状态" /><el-table-column prop="expires_at" label="过期时间" /></el-table>
    </div>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { ElMessageBox } from 'element-plus'
import http from '../api'
const d = ref({})
const cards = { shops: '店铺数', listings: '在管出价', anomalies: '异常商品', changes24h: '24h 改价次数', apiErrors24h: '24h 接口错误' }
const load = async () => (d.value = await http.get('/dashboard'))
const ctl = async a => { await http.post('/control/' + a); load() }
const confirmStop = () => ElMessageBox.confirm('紧急停止将立即阻止所有改价调用，确认？', '紧急停止', { type: 'error' }).then(() => ctl('emergency-stop'))
onMounted(load)
</script>
