<template>
  <el-container style="height:100vh">
    <el-aside width="200px" style="background:#001529">
      <div style="color:#fff;padding:16px;font-weight:bold">得物跟价管理</div>
      <el-menu router :default-active="$route.path" background-color="#001529" text-color="#ccc" active-text-color="#fff">
        <el-menu-item v-for="r in $router.options.routes" :key="r.path" :index="r.path">{{ r.meta.title }}</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header style="display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #eee">
        <span>{{ $route.meta.title }}</span>
        <el-alert type="warning" :closable="false" show-icon style="width:auto" title="真实得物接口尚未接入：当前仅 Mock 适配器可执行，默认 DRY-RUN" />
      </el-header>
      <el-main><router-view /></el-main>
    </el-container>
  </el-container>
</template>
