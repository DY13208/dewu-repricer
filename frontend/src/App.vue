<template>
  <div class="shell">
    <aside class="side">
      <div class="brand">
        <span class="brand-mark">跟</span>
        <span class="brand-name">得物跟价管理</span>
      </div>
      <nav class="nav">
        <div class="section-label nav-label">导航</div>
        <router-link v-for="r in $router.options.routes" :key="r.path" :to="r.path" class="nav-item" :class="{ active: $route.path === r.path }">
          <span class="dot" />{{ r.meta.title }}
        </router-link>
      </nav>
      <div class="side-foot">
        <button class="theme-btn" @click="toggleTheme">{{ dark ? '☀ 浅色' : '☾ 深色' }}</button>
      </div>
    </aside>
    <div class="main">
      <header class="head">
        <span class="crumb"><span class="mono muted">/</span> {{ $route.meta.title }}</span>
        <span class="notice"><span class="pulse" />真实得物接口尚未接入：当前仅 Mock 适配器可执行，默认 DRY-RUN</span>
      </header>
      <main class="content"><router-view /></main>
    </div>
  </div>
</template>
<script setup>
import { ref } from 'vue'
const dark = ref(localStorage.getItem('theme') === 'dark')
const apply = () => document.documentElement.classList.toggle('dark', dark.value)
apply()
const toggleTheme = () => { dark.value = !dark.value; localStorage.setItem('theme', dark.value ? 'dark' : 'light'); apply() }
</script>
<style scoped>
.shell { display: grid; grid-template-columns: 16rem minmax(0, 1fr); min-height: 100vh; }
.side { position: sticky; top: 0; height: 100vh; display: flex; flex-direction: column; border-right: 1px dashed var(--line-strong); background: var(--bg); }
.brand { height: 64px; display: flex; align-items: center; gap: 10px; padding: 0 16px; border-bottom: 1px dashed var(--line-strong); }
.brand-mark { width: 32px; height: 32px; border-radius: 999px; background: var(--accent); color: var(--accent-ink); display: grid; place-items: center; font-weight: 600; font-size: 14px; }
.brand-name { font-family: var(--font-display); font-weight: 600; font-size: 16px; letter-spacing: -.01em; }
.nav { flex: 1; overflow-y: auto; padding: 20px 10px; display: flex; flex-direction: column; gap: 2px; }
.nav-label { padding: 0 10px 8px; }
.nav-item { height: 36px; display: flex; align-items: center; gap: 10px; padding: 0 10px; border-radius: var(--radius-nav); color: var(--ink-2); text-decoration: none; transition: background .15s, color .15s; }
.nav-item:hover { color: var(--ink); background: var(--hover); }
.nav-item.active { color: var(--ink); background: var(--surface); box-shadow: 0 0 0 1px var(--line) inset, var(--shadow); font-weight: 500; }
.dot { width: 6px; height: 6px; border-radius: 999px; background: var(--line-strong); }
.nav-item.active .dot { background: var(--ink); }
.side-foot { padding: 12px; border-top: 1px dashed var(--line-strong); }
.theme-btn { width: 100%; height: 34px; border-radius: 999px; border: 1px solid var(--line-strong); background: var(--surface); color: var(--ink-2); font: inherit; cursor: pointer; }
.theme-btn:hover { color: var(--ink); }
.main { min-width: 0; display: flex; flex-direction: column; }
.head { height: 64px; position: sticky; top: 0; z-index: 10; display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 0 24px; border-bottom: 1px dashed var(--line-strong); background: color-mix(in srgb, var(--bg) 85%, transparent); backdrop-filter: blur(8px); }
.crumb { font-weight: 500; }
.muted { color: var(--ink-3); }
.notice { display: inline-flex; align-items: center; gap: 8px; font-size: 12px; color: var(--ink-2); padding: 6px 12px; border: 1px solid var(--line-strong); border-radius: 999px; background: var(--surface); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pulse { width: 7px; height: 7px; border-radius: 999px; background: var(--warn); box-shadow: 0 0 0 3px color-mix(in srgb, var(--warn) 25%, transparent); flex: none; }
.content { padding: 24px; flex: 1; }
</style>
