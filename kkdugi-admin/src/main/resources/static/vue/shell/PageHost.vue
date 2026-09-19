<template><component v-if="component" :is="component" ref="page" :key="menu.id" :menu-id="menu.id" :title="menu.title" :remarks="menu.remarks" :locale="locale"/></template>
<script setup>
import {ref} from 'vue';
defineProps({component:Object,menu:Object,locale:String});const page=ref(null);
defineExpose({beforeLeave:reason=>page.value?.beforeLeave?.(reason)??Promise.resolve(true)});
</script>
