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
  color: var(--jsp-text-secondary);

  .step-rail {
    display: flex;
    align-items: center;
    min-width: 0;
  }

  .step-node {
    width: 30px;
    height: 30px;
    border: 2px solid $border-darker;
    border-radius: 50%;
    background: var(--jsp-bg-card);
    color: $text-placeholder;
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
    background: $border-color;
    border-radius: 999px;
  }

  .step-content {
    padding-top: 10px;
    padding-right: 12px;
  }

  .step-title {
    color: var(--jsp-text-primary);
    font-size: 13px;
    font-weight: 600;
    line-height: 1.4;
    white-space: nowrap;
  }

  .step-status {
    margin-top: 4px;
    font-size: 12px;
    line-height: 1.3;
    color: $text-placeholder;
    white-space: nowrap;
  }

  &.is-success {
    .step-node {
      border-color: $success-color;
      background: $success-color;
      color: $text-inverse;
    }

    .step-line {
      background: $success-color;
    }

    .step-status {
      color: $success-color;
    }
  }

  &.is-running {
    .step-node {
      border-color: $primary-color;
      background: $primary-lighter;
      color: $primary-hover;
      box-shadow: 0 0 0 4px rgba(59, 130, 246, 0.12);
    }

    .running-icon {
      animation: step-spin 1.1s linear infinite;
    }

    .step-line {
      background: linear-gradient(90deg, $primary-color, $primary-light);
    }

    .step-status {
      color: $primary-hover;
    }
  }

  &.is-failed {
    .step-node {
      border-color: $danger-color;
      background: $danger-lighter;
      color: $danger-color;
    }

    .step-line {
      background: $danger-light;
    }

    .step-status {
      color: $danger-color;
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
