<template>
  <el-container style="height: 100vh">
    <el-header style="background: #001529; color: #fff; display: flex; align-items: center; padding: 0 20px;">
      <span style="font-size: 18px; font-weight: 600;">数据集成 ETL</span>
      <span style="margin-left: 16px; color: #999; font-size: 12px;">ETL OpenSource</span>
    </el-header>
    <el-container>
      <el-aside width="180px" style="background: #fff; border-right: 1px solid #eee;">
        <el-menu :default-active="active" @select="onSelect">
          <el-menu-item index="conn">
            <el-icon><Connection /></el-icon><span>数据源管理</span>
          </el-menu-item>
          <el-menu-item index="job">
            <el-icon><Operation /></el-icon><span>同步任务</span>
          </el-menu-item>
          <el-menu-item index="log">
            <el-icon><Document /></el-icon><span>执行日志</span>
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-main style="background: #f5f7fa; padding: 0;">
        <ConnView v-if="active === 'conn'" />
        <JobView v-else-if="active === 'job'" />
        <LogView v-else-if="active === 'log'" />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import ConnView from '@/views/dbtool/conn/index.vue'
import JobView from '@/views/etl/job/index.vue'
import LogView from '@/views/etl/log/index.vue'

const active = ref('job')
const onSelect = (idx: string) => { active.value = idx }
</script>
