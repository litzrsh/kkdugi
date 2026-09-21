<template><div class="batch-page message-page" :inert="saving||loading||undefined">
    <div class="page-heading"><div><h1 tabindex="-1">{{title||t('admmsge')}}</h1><p>{{remarks||t('message_desc')}}</p></div><div class="heading-icon"><i class="las la-language"></i></div></div>
    <div v-if="canWrite||canDelete" class="batch-bar" :class="{'has-changes':dirty}"><div class="batch-status"><span class="save-indicator"><i :class="dirty?'las la-pen':'las la-check'"></i></span><div><strong>{{t(dirty?'pending':'clean')}}</strong><small v-if="dirty">{{t('new')}} {{changes.insert.length}} · {{t('changed')}} {{changes.update.length}} · {{t('removed')}} {{changes.delete.length}}</small><small v-else>{{t('batch_hint')}}</small></div></div><div class="batch-buttons"><button class="button" :disabled="!dirty||saving" @click="discard">{{t('discard')}}</button><button class="button is-primary" :disabled="!dirty||saving" @click="save()"><i class="las la-check"></i>{{saving?t('saving'):t('save')}}<span v-if="dirty" class="button-counter">{{changeCount}}</span></button></div></div>
    <form class="search-panel" @submit.prevent="search"><div class="panel-heading"><span><i class="las la-filter"></i> {{t('filters')}}</span><button type="button" class="text-button filter-toggle" @click="filtersOpen=!filtersOpen" :aria-expanded="filtersOpen"><i :class="filtersOpen?'las la-angle-up':'las la-angle-down'"></i></button></div><div class="search-fields" v-show="filtersOpen"><div class="field" v-for="key in filterKeys" :key="key"><label class="label" :for="'search-'+key">{{t(key)}}</label><input class="input" :id="'search-'+key" :value="filters[key] || ''" @input="filters[key]=$event.target.value" :placeholder="t(key)" autocomplete="off"></div><div class="search-actions"><button type="button" class="button" @click="resetFilters"><i class="las la-undo-alt"></i>{{t('reset')}}</button><button class="button is-primary" :disabled="loading"><i class="las la-search"></i>{{t('search')}}</button></div></div><div class="collapsed-search" v-if="!filtersOpen"><span>{{Object.values(filters).filter(Boolean).join(' · ')||t('all')}}</span><button class="button is-primary" :disabled="loading">{{t('search')}}</button></div></form>
    <div v-if="error" class="error-panel page-error" role="alert"><i class="las la-exclamation-circle"></i><span>{{error}}</span><button class="text-button" @click="dirty?save():runLoad()">{{t('retry')}}</button></div>
    <section class="list-panel" :aria-label="t('admmsge')+' '+t('list')" :aria-busy="loading">
     <div class="list-toolbar"><div class="list-title"><h2>{{t('admmsge')}} <span class="count-chip">{{displayTotal}}</span></h2><span v-if="selected.length" class="selection-count">{{selected.length}} {{t('selected')}}</span></div><div class="toolbar-actions"><button class="icon-button refresh-button" :aria-label="t('refresh')" @click="refresh" :disabled="loading"><i class="las la-sync-alt"></i></button><button v-if="canDelete" class="button danger-ghost" :disabled="!selected.length" @click="removeRows(selected)"><i class="las la-trash-alt"></i>{{t('delete')}}</button><button v-if="canWrite" class="button is-primary is-light" @click="editRow()"><i class="las la-plus"></i>{{t('add')}}</button></div></div>
     <div class="grid-zone"><Grid ref="grid" :key="state.locale" :rows="rows" :columns="columns" :locale="state.locale" :title="t('admmsge')" :loading="loading" :inline-editing="canWrite" :selectable="canDelete" @selection="selected=$event" @edit="cellEdited"/><div v-if="!loading&&!rows.length" class="empty-state"><i class="las la-inbox"></i><h3>{{t('empty')}}</h3><p>{{t('empty_hint')}}</p><button v-if="canWrite" class="button" @click="editRow()"><i class="las la-plus"></i>{{t('add')}}</button></div></div>
     <div class="grid-footer"><span>{{t('total')}} <strong>{{displayTotal}}</strong> {{t('items')}}</span><div class="pagination-controls"><label class="page-size"><select :value="pageSize" @change="setPageSize(+$event.target.value)" :aria-label="t('page_size')"><option :value="20">20 / {{t('page')}}</option><option :value="50">50 / {{t('page')}}</option><option :value="100">100 / {{t('page')}}</option><option :value="200">200 / {{t('page')}}</option></select></label><button class="icon-button" :disabled="page<=1||loading" @click="movePage(page-1)" :aria-label="t('previous')"><i class="las la-angle-left"></i></button><span class="current-page">{{page}}</span><span class="page-total">/ {{Math.max(1,pages)}}</span><button class="icon-button" :disabled="page>=pages||loading" @click="movePage(page+1)" :aria-label="t('next')"><i class="las la-angle-right"></i></button></div></div>
    </section>
    <div class="below-grid"><p><i class="las la-info-circle"></i><span class="desktop-hint">{{t('message_inline_hint')}}</span><span class="mobile-hint">{{t('message_inline_hint')}}</span></p><span class="muted">kkdugi admin</span></div>

</div></template>
<script setup>
import {ref,computed,inject,onMounted,onBeforeUnmount,nextTick} from 'vue';
import Grid from '../components/Grid.vue';
import {languageOptions} from '@js/i18n/languages.mjs';
import {MessageCellEditor} from '@js/grid/MessageCellEditor.mjs';
const props=defineProps({permissions:Object,menuId:String,title:String,remarks:String});
const s=inject('kkdugi');const {t,state,api,clone,payload,rowState,validate,prepareBatch,restoreMenuRows}=s;
const filterKeys=['code', 'message'];
const canWrite=computed(()=>props.permissions?.['20']===true),canDelete=computed(()=>props.permissions?.['30']===true);
const filters=ref({}),applied=ref({}),rows=ref([]),baseline=ref([]),selected=ref([]),page=ref(1),pageSize=ref(20),pages=ref(1),total=ref(0),loading=ref(false),saving=ref(false),error=ref(''),tick=ref(0),grid=ref(null),filtersOpen=ref(true);
const displayTotal=total;
const langs=computed(()=>s.languages);let generation=0,disposed=false,request;
const changes=computed(()=>{tick.value;return payload(rows.value,baseline.value);});
const changeCount=computed(()=>changes.value.insert.length+changes.value.update.length+changes.value.delete.length);
const dirty=computed(()=>changeCount.value>0);
const dialog={open:options=>s.dialog.open({...options,ownerId:props.menuId}),confirm:async options=>(await dialog.open({kind:'confirm',...options})).status==='submitted'};
function errorText(e){if(e.validationKey)return t(e.validationKey);if(e.errors?.length)return e.errors.map(item=>[item.id||item.code,item.message||s.message(item.reason||item.code)].filter(Boolean).join(': ')).join(' · ');return e.status!==undefined?s.errorText(e):e.message;}
async function load(){
 request?.abort();request=new AbortController();const id=++generation;loading.value=true;error.value='';selected.value=[];
 try{const [result,languageCodes]=await Promise.all([api.list('i18n',{...applied.value,page:page.value,pageSize:pageSize.value},{signal:request.signal}),api.codes('/SYS/LANG',{children:true,locale:state.locale,signal:request.signal})]);if(id!==generation||disposed)return;
 s.languages.splice(0,s.languages.length,...languageOptions(languageCodes));if(!langs.value.length)error.value=t('no_languages');
 rows.value=result.contents.map(r=>({...r,_key:r.id||r.code}));baseline.value=clone(rows.value);total.value=result.totalItems;pages.value=result.totalPages;
 if(page.value>Math.max(1,pages.value)){page.value=Math.max(1,pages.value);return await load();}
 }catch(e){if(e.name==='AbortError'||disposed)return;if(id===generation){error.value=errorText(e);throw e;}}finally{if(id===generation)loading.value=false;}
}
async function runLoad(){try{await load();}catch{}}
async function guard(){
 grid.value?.stop();tick.value++;if(saving.value||state.dialogs.some(d=>d.ownerId===props.menuId))return false;if(!dirty.value)return true;
 const result=await dialog.open({kind:'confirm',title:t('unsaved'),message:t('unsaved_hint'),submitLabel:t('save_leave'),onSubmit:()=>save(true),alternative:{label:t('discard_leave'),action:()=>{rows.value=clone(baseline.value);}}});return result.status==='submitted';
}
async function search(){if(await guard()){applied.value=clone(filters.value);page.value=1;await runLoad();}}
function resetFilters(){filters.value={};}
async function refresh(){if(await guard())await runLoad();}
async function movePage(value){if(await guard()){page.value=value;await runLoad();}}
async function setPageSize(value){if(await guard()){pageSize.value=value;page.value=1;await runLoad();}}
function badge(text,kind=''){const el=document.createElement('span');el.className='status-badge '+kind;el.textContent=text;return el;}
function action(label,icon,fn,danger=false){const b=document.createElement('button');b.type='button';b.className='row-action'+(danger?' danger':'');b.title=label;b.setAttribute('aria-label',label);const i=document.createElement('i');i.className=icon;const span=document.createElement('span');span.textContent=label;b.append(i,span);b.onclick=e=>{e.stopPropagation();fn();};return b;}
const columns=computed(()=>{
 const list=[{colId:'status',headerName:t('status'),headerClass:'grid-center-header',cellClass:'grid-center-cell',width:84,minWidth:84,cellRenderer:p=>{const value=rowState(p.data,baseline.value);return badge(value?t(value):'—',value||'neutral');}}];
 const simple=(field,title,width=150,editable=false)=>({field,headerName:t(title),width,editable:p=>canWrite.value&&editable&&!p.data._deleted});
 list.push({...simple('code','code',260),editable:p=>canWrite.value&&!p.data._deleted&&!baseline.value.some(r=>r._key===p.data._key),cellClass:'mono-cell'});
 for(const lang of langs.value)list.push({colId:'locale_'+lang.code,headerName:lang.label+' · '+lang.code,width:270,cellClass:'message-cell',valueGetter:p=>p.data.locale?.[lang.code]||'',valueSetter:p=>{if(!canWrite.value)return false;p.data.locale??={};p.data.locale[lang.code]=p.newValue;return true;},editable:p=>canWrite.value&&!p.data._deleted,cellEditor:MessageCellEditor,cellEditorPopup:false});
 list.push({colId:'actions',headerName:t('actions'),headerClass:'grid-center-header',cellClass:'grid-center-cell',minWidth:190,width:220,cellRenderer:p=>{const el=document.createElement('div');el.className='row-actions';if(p.data._deleted){if(canDelete.value)el.append(action(t('restore'),'las la-undo',()=>restore(p.data)));return el;}
 if(canDelete.value)el.append(action(t('delete'),'las la-trash-alt',()=>removeRows([p.data]),true));return el;}});return list;
});
function cellEdited(event){
 tick.value++;rows.value=rows.value.map(row=>row._key===event.data._key?{...row}:row);
}
function newRow(){
 const locale=Object.fromEntries(langs.value.map(l=>[l.code,'']));
 return {code:'',locale};
}
async function editRow(){
 if(!canWrite.value||saving.value||loading.value)return;
 if(!langs.value.length){await dialog.open({kind:'alert',title:t('notice'),message:t('no_languages')});return;}
 grid.value?.stop();rows.value=[{...newRow(),_key:crypto.randomUUID()},...rows.value];tick.value++;await nextTick();grid.value?.edit(0,'code');
}
async function removeRows(targets){
 if(!canDelete.value||!targets.length||saving.value||loading.value)return;
 const ok=await dialog.confirm({title:t('confirm_delete'),message:targets.map(r=>r.code).join(', '),description:t('permanent'),danger:true,submitLabel:t('delete')});if(!ok||disposed)return;
 const group=crypto.randomUUID();for(const row of targets)if(!row._deleted){row._deleted=true;row._deleteGroup=group;}
 rows.value=rows.value.filter(r=>!(r._deleted&&!baseline.value.some(b=>b._key===r._key)));selected.value=[];grid.value?.clear();tick.value++;
}
function restore(row){if(!canDelete.value||saving.value||loading.value)return;restoreMenuRows(rows.value,row);rows.value=[...rows.value];tick.value++;}
async function discard(){if(await dialog.confirm({title:t('discard'),message:t('discard_form')})){rows.value=clone(baseline.value);selected.value=[];tick.value++;}}
async function save(throwError=false){
 if(saving.value||loading.value)return;grid.value?.stop();tick.value++;let batch=changes.value;if(!changeCount.value)return;saving.value=true;error.value='';
 try{
 if((!canWrite.value&&(batch.insert.length||batch.update.length))||(!canDelete.value&&batch.delete.length))throw Error(t('forbidden'));
 {const codes=rows.value.filter(r=>!r._deleted).map(r=>r.code);if(new Set(codes).size!==codes.length)throw Error(t('duplicate'));}
 batch=prepareBatch('i18n',batch,baseline.value);
 for(const row of [...batch.insert,...batch.update]){const e=validate('i18n',row,langs.value);if(e)throw Error(t(e));}
 await api.persist('i18n',batch);if(disposed)return;baseline.value=clone(rows.value.filter(r=>!r._deleted));rows.value=clone(baseline.value);tick.value++;s.notify('success');
 try{await load();}catch{rows.value=[];baseline.value=[];total.value=0;error.value=t('saved_reload');}
 }catch(e){if(!disposed)error.value=errorText(e);if(throwError)throw e;}finally{saving.value=false;}
}
function beforeUnload(e){if(dirty.value||state.dialogs.some(d=>d.ownerId===props.menuId)){e.preventDefault();e.returnValue='';}}
defineExpose({beforeLeave:guard});
onMounted(()=>{runLoad();window.addEventListener('beforeunload',beforeUnload);});
onBeforeUnmount(()=>{disposed=true;generation++;request?.abort();s.cancelDialogs(props.menuId);window.removeEventListener('beforeunload',beforeUnload);});
</script>
