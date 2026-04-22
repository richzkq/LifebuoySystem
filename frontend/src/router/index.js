import { createRouter, createWebHistory } from 'vue-router'
import Login from '../views/Login.vue'
import DetectionView from '../views/DetectionView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/login'
    },
    {
      path: '/login',
      name: 'login',
      component: Login
    },
    {
      path: '/detection',
      name: 'detection',
      component: DetectionView
    }
  ]
})

export default router
