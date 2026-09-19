<template><component v-if="component" :is="component" ref="page" :key="menu.id" :menu-id="menu.id" :title="menu.title" :remarks="menu.remarks" :locale="locale"/></template>
<script setup>
import {ref,inject,provide} from 'vue';
const props=defineProps({component:Object,menu:Object,locale:String});const page=ref(null);
const services=inject('kkdugi');
provide('kkdugi',{...services,api:services.api.forMenu(props.menu.id)});
defineExpose({beforeLeave:reason=>page.value?.beforeLeave?.(reason)??Promise.resolve(true)});
</script>
