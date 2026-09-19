<template><span class="mapping-avatar" aria-hidden="true"><img v-if="url&&!failed" :src="url" alt="" referrerpolicy="no-referrer" loading="lazy" @error="failed=true"><i v-else class="las la-user" aria-hidden="true"></i></span></template>
<script setup>
import {ref,computed,watch} from 'vue';
const props=defineProps({image:String});const failed=ref(false);
const url=computed(()=>{if(!props.image?.trim())return '';try{const parsed=new URL(props.image,location.href);return ['http:','https:'].includes(parsed.protocol)?parsed.href:'';}catch{return '';}});
watch(()=>props.image,()=>{failed.value=false;});
</script>
