import { createRouter, createWebHistory, RouteRecordRaw } from 'vue-router'
import LoginView from '@/views/LoginView.vue'
import MainLayout from '@/views/MainLayout.vue'
import { useUserStore } from '@/stores/user'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: LoginView
  },
  {
    path: '/',
    component: MainLayout,
    redirect: '/dashboard',
    children: [
      {
        path: '/dashboard',
        name: 'Dashboard',
        component: () => import('@/views/DashboardView.vue')
      },
      {
        path: '/workspace/projects',
        name: 'Projects',
        component: () => import('@/views/ProjectsView.vue')
      },
      {
        path: '/workspace/projects/:id',
        name: 'ProjectDetail',
        component: () => import('@/views/ProjectDetailView.vue')
      },
      {
        path: '/workspace/sql-statements/:id/construction',
        name: 'SqlConstructionDetail',
        component: () => import('@/views/SqlConstructionDetailView.vue')
      },
      {
        path: '/workspace/inspection',
        name: 'Inspection',
        component: () => import('@/views/InspectionView.vue')
      },
      {
        path: '/workspace/alert',
        name: 'Alert',
        component: () => import('@/views/AlertView.vue')
      },
      {
        path: '/workspace/rules',
        name: 'Rules',
        component: () => import('@/views/RulesView.vue')
      },
      {
        path: '/workspace/credentials',
        name: 'Credentials',
        component: () => import('@/views/CredentialsView.vue')
      },
      {
        path: '/workspace/repositories',
        name: 'Repositories',
        component: () => import('@/views/RepositoriesView.vue'),
        meta: { requiresAdmin: true }
      },
      {
        path: '/users',
        name: 'Users',
        component: () => import('@/views/UsersView.vue'),
        meta: { requiresAdmin: true }
      },
      {
        path: '/groups',
        name: 'Groups',
        component: () => import('@/views/GroupsView.vue'),
        meta: { requiresAdmin: true }
      },
      {
        path: '/profile',
        name: 'Profile',
        component: () => import('@/views/ProfileView.vue')
      },
      {
        path: '/settings',
        name: 'Settings',
        component: () => import('@/views/SettingsView.vue'),
        meta: { requiresAdmin: true }
      },
      {
        path: '/logs',
        name: 'Logs',
        component: () => import('@/views/LogsView.vue'),
        meta: { requiresAdmin: true }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, _from, next) => {
  const userStore = useUserStore()
  const token = userStore.token || localStorage.getItem('token')
  // 用户偏好：登录后默认落地页
  const landingPage = localStorage.getItem('jsqlint_landing_page')
  const preferredLanding = landingPage === '/workspace/projects' ? '/workspace/projects' : '/dashboard'

  if (to.path !== '/login' && !token) {
    next('/login')
  } else if (to.path === '/login' && token) {
    next(preferredLanding)
  } else {
    const requiresAdmin = to.meta.requiresAdmin
    if (requiresAdmin && userStore.role !== 'ROLE_ADMIN') {
      next(preferredLanding)
    } else {
      next()
    }
  }
})

export default router
