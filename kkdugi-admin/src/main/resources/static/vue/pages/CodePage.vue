<template><div class="batch-page code-page" :inert="saving||loading||undefined">
    <div class="page-heading"><div><h1 tabindex="-1">{{title||t('admcode')}}</h1><p>{{remarks||t('code_desc')}}</p></div><div class="heading-icon"><i class="las la-layer-group"></i></div></div>
    <div v-if="canWrite||canDelete" class="batch-bar" :class="{'has-changes':dirty}"><div class="batch-status"><span class="save-indicator"><i :class="dirty?'las la-pen':'las la-check'"></i></span><div><strong>{{t(dirty?'pending':'clean')}}</strong><small v-if="dirty">{{t('new')}} {{changes.insert.length}} · {{t('changed')}} {{changes.update.length}} · {{t('removed')}} {{changes.delete.length}}</small><small v-else>{{t('batch_hint')}}</small></div></div><div class="batch-buttons"><button class="button" :disabled="!dirty||saving" @click="discard">{{t('discard')}}</button><button class="button is-primary" :disabled="!dirty||saving" @click="save()"><i class="las la-check"></i>{{saving?t('saving'):t('save')}}<span v-if="dirty" class="button-counter">{{changeCount}}</span></button></div></div>
    <form class="search-panel" @submit.prevent="search"><div class="panel-heading"><span><i class="las la-filter"></i> {{t('filters')}}</span><button type="button" class="text-button filter-toggle" @click="filtersOpen=!filtersOpen" :aria-expanded="filtersOpen"><i :class="filtersOpen?'las la-angle-up':'las la-angle-down'"></i></button></div><div class="search-fields" v-show="filtersOpen"><div class="field" v-for="key in filterKeys" :key="key"><label class="label" :for="'search-'+key">{{t(key)}}</label><div v-if="key==='use'" class="select is-fullwidth"><select :id="'search-'+key" :value="filters[key] || ''" @input="filters[key]=$event.target.value"><option value="">{{t('all')}}</option><option v-for="option in ['Y','N']" :key="option" :value="option">{{t(option==='Y'?'enabled':'disabled')}}</option></select></div><input v-else class="input" :id="'search-'+key" :value="filters[key] || ''" @input="filters[key]=$event.target.value" :placeholder="t(key)" autocomplete="off"></div><div class="search-actions"><button type="button" class="button" @click="resetFilters"><i class="las la-undo-alt"></i>{{t('reset')}}</button><button class="button is-primary" :disabled="loading"><i class="las la-search"></i>{{t('search')}}</button></div></div><div class="collapsed-search" v-if="!filtersOpen"><span>{{Object.values(filters).filter(Boolean).join(' · ')||t('all')}}</span><button class="button is-primary" :disabled="loading">{{t('search')}}</button></div></form>
    <div v-if="error" class="error-panel page-error" role="alert"><i class="las la-exclamation-circle"></i><span>{{error}}</span><button class="text-button" @click="dirty?save():runLoad()">{{t('retry')}}</button></div>
    <section class="list-panel" :aria-label="t('admcode')+' '+t('list')" :aria-busy="loading">
     <div class="list-toolbar"><div class="list-title"><h2>{{t('admcode')}} <span class="count-chip">{{displayTotal}}</span></h2><span v-if="selected.length" class="selection-count">{{selected.length}} {{t('selected')}}</span></div><div class="toolbar-actions"><button class="icon-button refresh-button" :aria-label="t('refresh')" @click="refresh" :disabled="loading"><i class="las la-sync-alt"></i></button><button v-if="canDelete" class="button danger-ghost" :disabled="!selected.length" @click="removeRows(selected)"><i class="las la-trash-alt"></i>{{t('delete')}}</button><button v-if="canWrite" class="button is-primary is-light" @click="editRow()"><i class="las la-plus"></i>{{t('add')}}</button></div></div>
     <div class="code-breadcrumb"><button @click="goLevel(-1)" class="crumb-root"><i class="las la-folder-open"></i>{{t('root')}}</button><template v-for="(crumb,index) in trail" :key="crumb.id"><i class="las la-angle-right"></i><button @click="goLevel(index)">{{localeValue(crumb,state.locale)||crumb.code}}</button></template><button class="up-button" :disabled="!trail.length" @click="goLevel(trail.length-2)"><i class="las la-level-up-alt"></i>{{t('up')}}</button></div>
     <div class="grid-zone"><Grid ref="grid" :key="state.locale" :rows="rows" :columns="columns" :locale="state.locale" :title="t('admcode')" :loading="loading" :inline-editing="canWrite" :selectable="canDelete" @selection="selected=$event" @edit="cellEdited"/><div v-if="!loading&&!rows.length" class="empty-state"><i class="las la-inbox"></i><h3>{{t('empty')}}</h3><p>{{t('empty_hint')}}</p><button v-if="canWrite" class="button" @click="editRow()"><i class="las la-plus"></i>{{t('add')}}</button></div></div>
     <div class="grid-footer"><span>{{t('total')}} <strong>{{displayTotal}}</strong> {{t('items')}}</span><div class="pagination-controls"><label class="page-size"><select :value="pageSize" @change="setPageSize(+$event.target.value)" :aria-label="t('page_size')"><option :value="20">20 / {{t('page')}}</option><option :value="50">50 / {{t('page')}}</option><option :value="100">100 / {{t('page')}}</option><option :value="200">200 / {{t('page')}}</option></select></label><button class="icon-button" :disabled="page<=1||loading" @click="movePage(page-1)" :aria-label="t('previous')"><i class="las la-angle-left"></i></button><span class="current-page">{{page}}</span><span class="page-total">/ {{Math.max(1,pages)}}</span><button class="icon-button" :disabled="page>=pages||loading" @click="movePage(page+1)" :aria-label="t('next')"><i class="las la-angle-right"></i></button></div></div>
    </section>
    <div class="below-grid"><p><i class="las la-info-circle"></i><span class="desktop-hint">{{t('code_inline_hint')}}</span><span class="mobile-hint">{{t('code_inline_hint')}}</span></p><span class="muted">kkdugi admin</span></div>

</div></template>
<script setup>
import {ref,computed,inject,onMounted,onBeforeUnmount,nextTick} from 'vue';
import Grid from '../components/Grid.vue';
import {languageOptions} from '@js/i18n/languages.mjs';
import {CodeCellEditor} from '@js/grid/CodeCellEditor.mjs';
const props=defineProps({permissions:Object,menuId:String,title:String,remarks:String});
const s=inject('kkdugi');const {t,state,api,clone,payload,rowState,validate,prepareBatch,restoreMenuRows,localeValue,prepareLocalizedRow}=s;
const filterKeys=['path', 'code', 'name', 'use'];
const canWrite=computed(()=>props.permissions?.['20']===true),canDelete=computed(()=>props.permissions?.['30']===true);
const filters=ref({}),applied=ref({}),rows=ref([]),baseline=ref([]),selected=ref([]),trail=ref([]),page=ref(1),pageSize=ref(20),pages=ref(1),total=ref(0),loading=ref(false),saving=ref(false),error=ref(''),tick=ref(0),grid=ref(null),filtersOpen=ref(true);
const displayTotal=total;
const langs=computed(()=>s.languages);let generation=0,disposed=false,request;
const changes=computed(()=>{tick.value;return payload(rows.value,baseline.value);});
const changeCount=computed(()=>changes.value.insert.length+changes.value.update.length+changes.value.delete.length);
const dirty=computed(()=>changeCount.value>0);
const dialog={open:options=>s.dialog.open({...options,ownerId:props.menuId}),confirm:async options=>(await dialog.open({kind:'confirm',...options})).status==='submitted'};
function errorText(e){if(e.validationKey)return t(e.validationKey);if(e.errors?.length)return e.errors.map(item=>[item.id||item.code,item.message||s.message(item.reason||item.code)].filter(Boolean).join(': ')).join(' · ');return e.status!==undefined?s.errorText(e):e.message;}
async function load(){
 request?.abort();request=new AbortController();const id=++generation;loading.value=true;error.value='';selected.value=[];
 try{const [result,languageCodes]=await Promise.all([api.list('code',{...applied.value,page:page.value,pageSize:pageSize.value,parentId:trail.value.at(-1)?.id||null},{signal:request.signal}),api.codes('/SYS/LANG',{children:true,locale:state.locale,signal:request.signal})]);if(id!==generation||disposed)return;
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
async function goLevel(index){if(await guard()){trail.value=trail.value.slice(0,index+1);page.value=1;filters.value={};applied.value={};await runLoad();}}
async function descend(row){if(row.id&&await guard()){trail.value.push(clone(row));page.value=1;filters.value={};applied.value={};await runLoad();}}
function badge(text,kind=''){const el=document.createElement('span');el.className='status-badge '+kind;el.textContent=text;return el;}
function action(label,icon,fn,danger=false){const b=document.createElement('button');b.type='button';b.className='row-action'+(danger?' danger':'');b.title=label;b.setAttribute('aria-label',label);const i=document.createElement('i');i.className=icon;const span=document.createElement('span');span.textContent=label;b.append(i,span);b.onclick=e=>{e.stopPropagation();fn();};return b;}
const columns=computed(()=>{
 const list=[{colId:'status',headerName:t('status'),headerClass:'grid-center-header',cellClass:'grid-center-cell',width:84,minWidth:84,cellRenderer:p=>{const value=rowState(p.data,baseline.value);return badge(value?t(value):'—',value||'neutral');}}];
 const simple=(field,title,width=150,editable=false)=>({field,headerName:t(title),width,editable:p=>canWrite.value&&editable&&!p.data._deleted});
 const editable=p=>canWrite.value&&!p.data._deleted;
 list.push({...simple('code','code',175),editable:p=>editable(p)&&!p.data.id,cellClass:p=>'mono-cell '+(!p.data.id&&canWrite.value?'code-editable-cell':''),cellEditor:CodeCellEditor,tooltipValueGetter:p=>p.data.id?t('code_locked_hint'):t('invalid_common_code')});
 list.push({...simple('use','use',115),editable,cellClass:canWrite.value?'code-editable-cell':'',cellEditor:CodeCellEditor,cellEditorParams:{kind:'select',options:[{value:'Y',label:t('enabled')},{value:'N',label:t('disabled')}]},cellRenderer:p=>badge(t(p.value==='Y'?'enabled':'disabled'),p.value==='Y'?'active':'neutral')});
 list.push({...simple('sort','sort',100),editable,cellClass:canWrite.value?'code-editable-cell':'',cellEditor:CodeCellEditor,cellEditorParams:{kind:'number'}});
 list.push({colId:'code_details',headerName:t('code_details'),width:300,minWidth:220,cellRenderer:p=>{
  const el=document.createElement('div');el.className='code-details-cell';const summary=document.createElement('span');summary.className='code-details-summary';
  const name=document.createElement('strong');name.textContent=localeValue(p.data,state.locale)||t('untranslated');const remarks=document.createElement('small');remarks.textContent=localeValue(p.data,state.locale,'remarks')||'—';summary.append(name,remarks);summary.title=name.textContent+'\n'+remarks.textContent;el.append(summary);
  if(!p.data._deleted){const button=action(t(canWrite.value?'edit':'view'),'las la-language',()=>editCodeDetails(p.data));button.setAttribute('aria-label',t('code_details')+' · '+(p.data.code||t('new')));el.append(button);}return el;
 }});
 list.push({colId:'code_extras',headerName:t('extra'),width:160,minWidth:145,cellRenderer:p=>{
  if(p.data._deleted)return '';const count=[1,2,3,4,5].filter(n=>p.data['extra'+n]?.trim()).length;
  const button=action(t('extra')+' '+count+'/5','las la-sliders-h',()=>editCodeExtras(p.data));button.setAttribute('aria-label',t('extra')+' · '+(p.data.code||t('new')));return button;
 }});
 list.push({colId:'actions',headerName:t('actions'),headerClass:'grid-center-header',cellClass:'grid-center-cell',minWidth:190,width:190,cellRenderer:p=>{const el=document.createElement('div');el.className='row-actions';if(p.data._deleted){if(canDelete.value)el.append(action(t('restore'),'las la-undo',()=>restore(p.data)));return el;}
 if(p.data.id)el.append(action(t('children'),'las la-folder-open',()=>descend(p.data)));
 if(canDelete.value)el.append(action(t('delete'),'las la-trash-alt',()=>removeRows([p.data]),true));return el;}});return list;
});
function cellEdited(event){
 if(!event.data.id&&event.colDef.field==='code')event.data.path=(trail.value.at(-1)?.path||'')+'/'+event.data.code;
 tick.value++;rows.value=rows.value.map(row=>row._key===event.data._key?{...row}:row);
}
function applyCodeFields(key,fields){
 if(disposed||!canWrite.value)return;const index=rows.value.findIndex(r=>r._key===key&&!r._deleted);if(index<0)return;
 rows.value[index]={...rows.value[index],...fields};rows.value=[...rows.value];tick.value++;
}
async function editCodeDetails(row){
 if(saving.value||loading.value||row._deleted)return;if(!langs.value.length){await dialog.open({kind:'alert',title:t('notice'),message:t('no_languages')});return;}grid.value?.stop();
 const original=baseline.value.find(r=>r._key===row._key);
 await dialog.open({kind:'form',title:t('code_details'),subtitle:row.code||t('new'),batch:canWrite.value,readonly:!canWrite.value,initial:{locale:clone(row.locale||{})},translation:'name',languages:[...langs.value],validate:value=>{
  try{prepareLocalizedRow('code',{...row,locale:value.locale},original);return '';}catch(e){return errorText(e);}
 },onSubmit:value=>applyCodeFields(row._key,{locale:prepareLocalizedRow('code',{...row,locale:value.locale},original).locale})});
}
async function editCodeExtras(row){
 if(saving.value||loading.value||row._deleted)return;grid.value?.stop();
 const initial=clone(Object.fromEntries([1,2,3,4,5].map(n=>['extra'+n,row['extra'+n]])));
 await dialog.open({kind:'form',title:t('extra'),subtitle:row.code||t('new'),batch:canWrite.value,readonly:!canWrite.value,initial,extraTabs:true,onSubmit:value=>applyCodeFields(row._key,value)});
}
function newRow(){
 const locale=Object.fromEntries(langs.value.map(l=>[l.code,{name:'',remarks:''}]));
 return {id:'',parentId:trail.value.at(-1)?.id||null,code:'',locale,use:'Y',path:trail.value.at(-1)?.path||'',level:trail.value.length,sort:0,extra1:'',extra2:'',extra3:'',extra4:'',extra5:''};
}
async function editRow(){
 if(!canWrite.value||saving.value||loading.value)return;
 if(!langs.value.length){await dialog.open({kind:'alert',title:t('notice'),message:t('no_languages')});return;}
 grid.value?.stop();rows.value=[{...newRow(),_key:crypto.randomUUID()},...rows.value];tick.value++;await nextTick();grid.value?.edit(0,'code');
}
async function removeRows(targets){
 if(!canDelete.value||!targets.length||saving.value||loading.value)return;
 const ok=await dialog.confirm({title:t('confirm_delete'),message:targets.map(r=>r.code).join(', '),description:t('cascade'),danger:true,submitLabel:t('delete')});if(!ok||disposed)return;
 const group=crypto.randomUUID();for(const row of targets)if(!row._deleted){row._deleted=true;row._deleteGroup=group;}
 rows.value=rows.value.filter(r=>!(r._deleted&&!baseline.value.some(b=>b._key===r._key)));selected.value=[];grid.value?.clear();tick.value++;
}
function restore(row){if(!canDelete.value||saving.value||loading.value)return;restoreMenuRows(rows.value,row);rows.value=[...rows.value];tick.value++;}
async function discard(){if(await dialog.confirm({title:t('discard'),message:t('discard_form')})){rows.value=clone(baseline.value);selected.value=[];tick.value++;}}
async function save(throwError=false){
 if(saving.value||loading.value)return;grid.value?.stop();tick.value++;let batch=changes.value;if(!changeCount.value)return;saving.value=true;error.value='';
 try{
 if((!canWrite.value&&(batch.insert.length||batch.update.length))||(!canDelete.value&&batch.delete.length))throw Error(t('forbidden'));
 batch=prepareBatch('code',batch,baseline.value);
 for(const row of [...batch.insert,...batch.update]){const e=validate('code',row,langs.value);if(e)throw Error(t(e));}
 await api.persist('code',batch);if(disposed)return;baseline.value=clone(rows.value.filter(r=>!r._deleted));rows.value=clone(baseline.value);tick.value++;s.notify('success');
 try{await load();}catch{rows.value=[];baseline.value=[];total.value=0;error.value=t('saved_reload');}
 }catch(e){if(!disposed)error.value=errorText(e);if(throwError)throw e;}finally{saving.value=false;}
}
function beforeUnload(e){if(dirty.value||state.dialogs.some(d=>d.ownerId===props.menuId)){e.preventDefault();e.returnValue='';}}
defineExpose({beforeLeave:guard});
onMounted(()=>{runLoad();window.addEventListener('beforeunload',beforeUnload);});
onBeforeUnmount(()=>{disposed=true;generation++;request?.abort();s.cancelDialogs(props.menuId);window.removeEventListener('beforeunload',beforeUnload);});
</script>
