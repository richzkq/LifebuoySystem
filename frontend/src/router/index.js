import { createRouter, createWebHistory } from 'vue-router'
import { STORAGE_KEY } from '@/config'

const Login = () => import('../views/Login.vue')
const DetectionView = () => import('../views/DetectionView.vue')

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/login',
    },
    {
      path: '/login',
      name: 'login',
      component: Login,
      meta: { requiresAuth: false },
    },
    {
      path: '/detection',
      name: 'detection',
      component: DetectionView,
      meta: { requiresAuth: true },
    },
  ],
})

// ============ 导航守卫 ============
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem(STORAGE_KEY.TOKEN)

  if (to.meta.requiresAuth && !token) {
    next('/login')
  } else if (to.path === '/login' && token) {
    next('/detection')
  } else {
    next()
  }
})

export default router
