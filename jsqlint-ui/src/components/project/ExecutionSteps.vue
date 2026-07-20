<template>
  <div class="execution-steps" :style="{ '--step-count': String(steps.length) }">
    <div
      v-for="(step, index) in steps"
      :key="step.key"
      class="execution-step"
      :class="`is-${step.status}`"
    >
      <div class="step-rail">
        <div class="step-node">
          <el-icon v-if="step.status === 'success'"><Check /></el-icon>
          <el-icon v-else-if="step.status === 'failed'"><Close /></el-icon>
          <el-icon v-else-if="step.status === 'running'" class="running-icon"><Loading /></el-icon>
          <el-icon v-else><Minus /></el-icon>
        </div>
        <div v-if="index < steps.length - 1" class="step-line" />
      </div>
      <div class="step-content">
        <div class="step-title">{{ step.label }}</div>
        <div class="step-status">{{ statusLabels[step.status] }}</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Check, Close, Loading, Minus } from '@element-plus/icons-vue'

export type ExecutionStepStatus = 'pending' | 'running' | 'success' | 'failed'

export interface ExecutionStep {
  key: string
  label: string
  status: ExecutionStepStatus
}

defineProps<{
  steps: ExecutionStep[]
}>()

const { t } = useI18n()

const statusLabels = computed<Record<ExecutionStepStatus, string>>(() => ({
  pending: t('projectDetail.stepStatus.pending'),
  running: t('projectDetail.stepStatus.running'),
  success: t('projectDetail.stepStatus.success'),
  failed: t('projectDetail.stepStatus.failed')
}))
</script>

<style lang="scss" scoped>
.execution-steps {
  display: grid;
  grid-template-columns: repeat(var(--step-count), minmax(0, 1fr));
  gap: 0;
  width: 100%;
}

.execution-step {
  min-width: 0;
  color: #64748b;

  .step-rail {
    display: flex;
    align-items: center;
    min-width: 0;
  }

  .step-node {
    width: 30px;
    height: 30px;
    border: 2px solid #cbd5e1;
    border-radius: 50%;
    background: #ffffff;
    color: #94a3b8;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    transition: all 0.2s ease;

    .el-icon {
      font-size: 16px;
    }
  }

  .step-line {
    height: 2px;
    min-width: 16px;
    flex: 1;
    margin: 0 10px;
    background: #e2e8f0;
    border-radius: 999px;
  }

  .step-content {
    padding-top: 10px;
    padding-right: 12px;
  }

  .step-title {
    color: #1e293b;
    font-size: 13px;
    font-weight: 600;
    line-height: 1.4;
    white-space: nowrap;
  }

  .step-status {
    margin-top: 4px;
    font-size: 12px;
    line-height: 1.3;
    color: #94a3b8;
    white-space: nowrap;
  }

  &.is-success {
    .step-node {
      border-color: #10b981;
      background: #10b981;
      color: #ffffff;
    }

    .step-line {
      background: #10b981;
    }

    .step-status {
      color: #059669;
    }
  }

  &.is-running {
    .step-node {
      border-color: #3b82f6;
      background: #eff6ff;
      color: #2563eb;
      box-shadow: 0 0 0 4px rgba(59, 130, 246, 0.12);
    }

    .running-icon {
      animation: step-spin 1.1s linear infinite;
    }

    .step-line {
      background: linear-gradient(90deg, #3b82f6, #dbeafe);
    }

    .step-status {
      color: #2563eb;
    }
  }

  &.is-failed {
    .step-node {
      border-color: #ef4444;
      background: #fef2f2;
      color: #dc2626;
    }

    .step-line {
      background: #fecaca;
    }

    .step-status {
      color: #dc2626;
    }
  }
}

@keyframes step-spin {
  from {
    transform: rotate(0deg);
  }

  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 768px) {
  .execution-steps {
    grid-template-columns: 1fr;
    gap: 14px;
  }

  .execution-step {
    display: grid;
    grid-template-columns: 30px 1fr;
    column-gap: 12px;

    .step-rail {
      align-items: flex-start;
      justify-content: center;
      grid-row: span 2;
    }

    .step-line {
      display: none;
    }

    .step-content {
      padding-top: 0;
      padding-right: 0;
    }
  }
}
</style>
