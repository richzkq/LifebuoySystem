<template>
  <router-view></router-view>
</template>

<style>
/* Reset basic styles to ensure full screen login page */
/* html, body {
  margin: 0;
  padding: 0;
  width: 100%;
  height: 100%;
} */

  html, body, #app {
    margin: 0;
    padding: 0;
    height: 100vh;
    width: 100vw;
    overflow-x: hidden;
    overflow-y: auto;
    scrollbar-width: none;
    -ms-overflow-style: none;
  }

/* #app {
  width: 100%;
  height: 100%;
} */

  html::-webkit-scrollbar,
  body::-webkit-scrollbar,
  #app::-webkit-scrollbar {
    display: none;
    width: 0;
    height: 0;
  }

  html {
    scrollbar-gutter: stable;
  }

</style>

