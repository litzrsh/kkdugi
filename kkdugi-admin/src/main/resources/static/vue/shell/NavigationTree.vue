<template>
 <ul class="navigation-tree"><li v-for="node in nodes" :key="node.id">
  <button class="nav-item" :class="{active:activeId===node.id}" :style="{paddingLeft:(16+depth*16)+'px'}" :aria-expanded="node.children.length?expanded[node.id]!==false:undefined" :aria-current="activeId===node.id?'page':undefined" @click="node.children.length?toggle(node.id):$emit('select',node)">
   <i :class="icon(node.icon)" aria-hidden="true"></i><span>{{node.title}}</span><i v-if="node.children.length" :class="expanded[node.id]===false?'las la-angle-right':'las la-angle-down'" aria-hidden="true"></i>
  </button>
  <NavigationTree v-if="node.children.length&&expanded[node.id]!==false" :nodes="node.children" :depth="depth+1" :active-id="activeId" @select="$emit('select',$event)"/>
 </li></ul>
</template>
<script setup>
import {reactive,inject} from 'vue';
defineProps({nodes:Array,activeId:String,depth:{type:Number,default:0}});defineEmits(['select']);
const expanded=reactive({});const icon=inject('kkdugi').menuIcon;
function toggle(id){expanded[id]=expanded[id]===false;}
</script>
