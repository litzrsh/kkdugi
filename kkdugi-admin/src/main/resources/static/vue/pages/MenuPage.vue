<template><div class="batch-page menu-page" :inert="saving||loading||undefined">
    <div class="page-heading"><div><h1 tabindex="-1">{{title||t('admmenu')}}</h1><p>{{remarks||t('menu_desc')}}</p></div><div class="heading-icon"><i class="las la-sitemap"></i></div></div>
    <div v-if="canWrite||canDelete" class="batch-bar" :class="{'has-changes':dirty}"><div class="batch-status"><span class="save-indicator"><i :class="dirty?'las la-pen':'las la-check'"></i></span><div><strong>{{t(dirty?'pending':'clean')}}</strong><small v-if="dirty">{{t('new')}} {{changes.insert.length}} · {{t('changed')}} {{changes.update.length}} · {{t('removed')}} {{changes.delete.length}}</small><small v-else>{{t('batch_hint')}}</small></div></div><div class="batch-buttons"><button class="button" :disabled="!dirty||saving" @click="discard">{{t('discard')}}</button><button class="button is-primary" :disabled="!dirty||saving" @click="save()"><i class="las la-check"></i>{{saving?t('saving'):t('save')}}<span v-if="dirty" class="button-counter">{{changeCount}}</span></button></div></div>
    <form class="search-panel" @submit.prevent="search"><div class="panel-heading"><span><i class="las la-filter"></i> {{t('filters')}}</span><button type="button" class="text-button filter-toggle" @click="filtersOpen=!filtersOpen" :aria-expanded="filtersOpen"><i :class="filtersOpen?'las la-angle-up':'las la-angle-down'"></i></button></div><div class="search-fields" v-show="filtersOpen"><div class="field" v-for="key in filterKeys" :key="key"><label class="label" :for="'search-'+key">{{t(key)}}</label><input class="input" :id="'search-'+key" :value="filters[key] || ''" @input="filters[key]=$event.target.value" :placeholder="t(key)" autocomplete="off"></div><div class="search-actions"><button type="button" class="button" @click="resetFilters"><i class="las la-undo-alt"></i>{{t('reset')}}</button><button class="button is-primary" :disabled="loading"><i class="las la-search"></i>{{t('search')}}</button></div></div><div class="collapsed-search" v-if="!filtersOpen"><span>{{Object.values(filters).filter(Boolean).join(' · ')||t('all')}}</span><button class="button is-primary" :disabled="loading">{{t('search')}}</button></div></form>
    <div v-if="error" class="error-panel page-error" role="alert"><i class="las la-exclamation-circle"></i><span>{{error}}</span><button class="text-button" @click="dirty?save():runLoad()">{{t('retry')}}</button></div>
    <section class="list-panel" :aria-label="t('admmenu')+' '+t('list')" :aria-busy="loading">
     <div class="list-toolbar"><div class="list-title"><h2>{{t('admmenu')}} <span class="count-chip">{{displayTotal}}</span></h2><span v-if="selected.length" class="selection-count">{{selected.length}} {{t('selected')}}</span></div><div class="toolbar-actions"><button class="icon-button refresh-button" :aria-label="t('refresh')" @click="refresh" :disabled="loading"><i class="las la-sync-alt"></i></button><button v-if="canDelete" class="button danger-ghost" :disabled="!selected.length" @click="removeRows(selected)"><i class="las la-trash-alt"></i>{{t('delete')}}</button><button v-if="canWrite" class="button is-primary is-light" @click="editRow()"><i class="las la-plus"></i>{{t('add')}}</button></div></div>
     <div class="grid-zone"><Grid ref="grid" :key="state.locale" :rows="visibleRows" :columns="columns" :locale="state.locale" :title="t('admmenu')" :loading="loading" :selectable="canDelete" @selection="selected=$event"/><div v-if="!loading&&!visibleRows.length" class="empty-state"><i class="las la-inbox"></i><h3>{{t('empty')}}</h3><p>{{t('empty_hint')}}</p><button v-if="canWrite" class="button" @click="editRow()"><i class="las la-plus"></i>{{t('add')}}</button></div></div>
     <div class="grid-footer"><span>{{t('total')}} <strong>{{displayTotal}}</strong> {{t('items')}}</span><span class="muted">{{t('system')}}</span></div>
    </section>
    <div class="below-grid"><p><i class="las la-info-circle"></i><span class="desktop-hint">{{t('edit_hint')}}</span><span class="mobile-hint">{{t('mobile_hint')}}</span></p><span class="muted">kkdugi admin</span></div>

</div></template>
<script setup>
import {ref,computed,inject,onMounted,onBeforeUnmount} from 'vue';
import Grid from '../components/Grid.vue';
import {languageOptions} from '@js/i18n/languages.mjs';
const props=defineProps({permissions:Object,menuId:String,title:String,remarks:String});
const s=inject('kkdugi');const {t,state,api,clone,payload,rowState,validate,prepareBatch,restoreMenuRows,localeValue,flatten,isProtected,prepareLocalizedRow,menuDescendants,filterMenuRows,markMenuDeleted,menuLabel}=s;
const filterKeys=['name'];
const canWrite=computed(()=>props.permissions?.['20']===true),canDelete=computed(()=>props.permissions?.['30']===true);
const filters=ref({}),applied=ref({}),rows=ref([]),baseline=ref([]),selected=ref([]),loading=ref(false),saving=ref(false),error=ref(''),tick=ref(0),grid=ref(null),filtersOpen=ref(true);
const displayTotal=computed(()=>rows.value.length);
const langs=computed(()=>s.languages);let generation=0,disposed=false,request;
const changes=computed(()=>{tick.value;return payload(rows.value,baseline.value);});
const changeCount=computed(()=>changes.value.insert.length+changes.value.update.length+changes.value.delete.length);
const dirty=computed(()=>changeCount.value>0);
const visibleRows=computed(()=>filterMenuRows(rows.value,applied.value.name,state.locale));
const dialog={open:options=>s.dialog.open({...options,ownerId:props.menuId}),confirm:async options=>(await dialog.open({kind:'confirm',...options})).status==='submitted'};
function errorText(e){if(e.validationKey)return t(e.validationKey);if(e.errors?.length)return e.errors.map(item=>[item.id||item.code,item.message||s.message(item.reason||item.code)].filter(Boolean).join(': ')).join(' · ');return e.status!==undefined?s.errorText(e):e.message;}
async function load(){
 request?.abort();request=new AbortController();const id=++generation;loading.value=true;error.value='';selected.value=[];
 try{const [result,languageCodes]=await Promise.all([api.list('menu',{},{signal:request.signal}),api.codes('/SYS/LANG',{children:true,locale:state.locale,signal:request.signal})]);if(id!==generation||disposed)return;
 s.languages.splice(0,s.languages.length,...languageOptions(languageCodes));if(!langs.value.length)error.value=t('no_languages');
 rows.value=flatten(result).map(r=>({...r,_key:r.id||r.code}));baseline.value=clone(rows.value);
 }catch(e){if(e.name==='AbortError'||disposed)return;if(id===generation){error.value=errorText(e);throw e;}}finally{if(id===generation)loading.value=false;}
}
async function runLoad(){try{await load();}catch{}}
async function guard(){
 grid.value?.stop();tick.value++;if(saving.value||state.dialogs.some(d=>d.ownerId===props.menuId))return false;if(!dirty.value)return true;
 const result=await dialog.open({kind:'confirm',title:t('unsaved'),message:t('unsaved_hint'),submitLabel:t('save_leave'),onSubmit:()=>save(true),alternative:{label:t('discard_leave'),action:()=>{rows.value=clone(baseline.value);}}});return result.status==='submitted';
}
async function search(){applied.value=clone(filters.value);}
function resetFilters(){filters.value={};applied.value={};}
async function refresh(){if(await guard())await runLoad();}
function badge(text,kind=''){const el=document.createElement('span');el.className='status-badge '+kind;el.textContent=text;return el;}
function action(label,icon,fn,danger=false){const b=document.createElement('button');b.type='button';b.className='row-action'+(danger?' danger':'');b.title=label;b.setAttribute('aria-label',label);const i=document.createElement('i');i.className=icon;const span=document.createElement('span');span.textContent=label;b.append(i,span);b.onclick=e=>{e.stopPropagation();fn();};return b;}
const columns=computed(()=>{
 const list=[{colId:'status',headerName:t('status'),headerClass:'grid-center-header',cellClass:'grid-center-cell',width:84,minWidth:84,cellRenderer:p=>{const value=rowState(p.data,baseline.value);return badge(value?t(value):'—',value||'neutral');}}];
 const simple=(field,title,width=150,editable=false)=>({field,headerName:t(title),width,editable:p=>canWrite.value&&editable&&!p.data._deleted});
 list.push({headerName:t('label'),width:270,valueGetter:p=>menuLabel(p.data,state.locale),cellRenderer:p=>{const el=document.createElement('span');el.className='name-cell';el.style.paddingLeft=(p.data._depth||0)*18+'px';const icon=document.createElement('i');icon.className=p.data.program?'las la-window-maximize':'las la-folder';icon.setAttribute('aria-hidden','true');el.append(icon,document.createTextNode(' '+(p.value||'—')));return el;}},simple('program','program',160),{...simple('use','use',110),cellRenderer:p=>badge(t(p.value==='Y'?'enabled':'disabled'),p.value==='Y'?'active':'neutral')},{...simple('close','closable',120),cellRenderer:p=>badge(t(p.value==='Y'?'enabled':'disabled'),p.value==='Y'?'active':'neutral')},simple('sort','sort',95));
 list.push({colId:'actions',headerName:t('actions'),headerClass:'grid-center-header',cellClass:'grid-center-cell',minWidth:190,width:300,cellRenderer:p=>{const el=document.createElement('div');el.className='row-actions';if(p.data._deleted){if(canDelete.value)el.append(action(t('restore'),'las la-undo',()=>restore(p.data)));return el;}
 if(canWrite.value){el.append(action(t('edit'),'las la-pen',()=>editRow(p.data)));if(!isProtected(p.data,rows.value))el.append(action(t('add_child'),'las la-plus',()=>editRow(null,p.data)));}
 if(canDelete.value&&!isProtected(p.data,rows.value))el.append(action(t('delete'),'las la-trash-alt',()=>removeRows([p.data]),true));return el;}});return list;
});
function newRow(parent){
 const locale=Object.fromEntries(langs.value.map(l=>[l.code,{label:'',remarks:''}]));
 return {id:'',parentId:parent?.id||null,program:'',icon:'las la-folder',locale,use:'Y',close:'Y',sort:0,_depth:parent?(parent._depth||0)+1:0};
}
async function editRow(row=null,parent=null){
 if(!canWrite.value||saving.value||loading.value)return;
 if(!langs.value.length){await dialog.open({kind:'alert',title:t('notice'),message:t('no_languages')});return;}
 if(parent&&isProtected(parent,rows.value))return;
 if(parent&&!parent.id){s.notify('menu_parent_pending');return;}
 const initial=row?clone(row):newRow(parent),fixed=row&&isProtected(row,rows.value);
 const fields=[];const add=(path,label,type='text',extra={})=>fields.push({path,label:t(label),type,...extra});
 initial._parentLabel=menuLabel(rows.value.find(r=>r.id&&r.id===initial.parentId),state.locale)||t('root');
 add('_parentLabel','parent','text',{readonly:true});add('program','program','text',{readonly:fixed,hint:t('program_hint'),placeholder:'admin/menu'});add('icon','icon','text');
 add('use','use','select',{readonly:fixed,options:[{value:'Y',label:t('enabled')},{value:'N',label:t('disabled')}]});
 add('close','closable','select',{readonly:fixed,options:[{value:'Y',label:t('enabled')},{value:'N',label:t('disabled')}]});
 add('sort','sort','number',{readonly:fixed});
 const original=row?baseline.value.find(r=>r._key===row._key):undefined;
 const languages=[...langs.value];
 await dialog.open({kind:'form',title:t(row?'edit':'add')+' · '+(props.title||t('admmenu')),subtitle:row?.code||row?.program,batch:true,wide:true,initial,fields,translation:'label',languages,validate:value=>{
  try{const e=validate('menu',prepareLocalizedRow('menu',value,original),langs.value);return e?t(e):'';}catch(e){return errorText(e);}
 },onSubmit:value=>{
  if(disposed||!canWrite.value)return;
  const clean=prepareLocalizedRow('menu',value,original);Object.assign(value,{locale:clean.locale,parentId:clean.parentId});
  value._key=row?._key||crypto.randomUUID();
  if(row)rows.value.splice(rows.value.findIndex(r=>r._key===row._key),1,value);
  else if(parent){const descendants=menuDescendants(rows.value,[parent]);const index=Math.max(...descendants.map(r=>rows.value.indexOf(r)));rows.value.splice(index+1,0,value);}
  else rows.value.unshift(value);
  rows.value=[...rows.value];tick.value++;
 }});
}
async function removeRows(targets){
 if(!canDelete.value||!targets.length||saving.value||loading.value)return;
 if(targets.some(r=>isProtected(r,rows.value))){await dialog.open({kind:'alert',title:t('notice'),message:t('protected')});return;}
 const ok=await dialog.confirm({title:t('confirm_delete'),message:targets.map(r=>localeValue(r,state.locale,'label')).join(', '),description:t('cascade'),danger:true,submitLabel:t('delete')});if(!ok||disposed)return;
 const group=crypto.randomUUID();markMenuDeleted(rows.value,targets,group);
 rows.value=rows.value.filter(r=>!(r._deleted&&!baseline.value.some(b=>b._key===r._key)));selected.value=[];grid.value?.clear();tick.value++;
}
function restore(row){if(!canDelete.value||saving.value||loading.value)return;restoreMenuRows(rows.value,row);rows.value=[...rows.value];tick.value++;}
async function discard(){if(await dialog.confirm({title:t('discard'),message:t('discard_form')})){rows.value=clone(baseline.value);selected.value=[];tick.value++;}}
async function save(throwError=false){
 if(saving.value||loading.value)return;grid.value?.stop();tick.value++;let batch=changes.value;if(!changeCount.value)return;saving.value=true;error.value='';
 try{
 if((!canWrite.value&&(batch.insert.length||batch.update.length))||(!canDelete.value&&batch.delete.length))throw Error(t('forbidden'));
 batch=prepareBatch('menu',batch,baseline.value);
 for(const row of [...batch.insert,...batch.update]){const e=validate('menu',row,langs.value);if(e)throw Error(t(e));}
 await api.persist('menu',batch);if(disposed)return;baseline.value=clone(rows.value.filter(r=>!r._deleted));rows.value=clone(baseline.value);tick.value++;s.notify('menu_saved');
 try{await load();}catch{rows.value=[];baseline.value=[];error.value=t('saved_reload');}
 }catch(e){if(!disposed)error.value=errorText(e);if(throwError)throw e;}finally{saving.value=false;}
}
function beforeUnload(e){if(dirty.value||state.dialogs.some(d=>d.ownerId===props.menuId)){e.preventDefault();e.returnValue='';}}
defineExpose({beforeLeave:guard});
onMounted(()=>{runLoad();window.addEventListener('beforeunload',beforeUnload);});
onBeforeUnmount(()=>{disposed=true;generation++;request?.abort();s.cancelDialogs(props.menuId);window.removeEventListener('beforeunload',beforeUnload);});
</script>
