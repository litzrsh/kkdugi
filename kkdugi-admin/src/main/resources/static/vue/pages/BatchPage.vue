<template><div class="batch-page" :inert="saving||loading||undefined">
    <div class="page-heading"><div><div class="eyebrow">SYSTEM MANAGEMENT <span class="eyebrow-line"></span> {{screen.toUpperCase()}}</div><h1 tabindex="-1">{{title||t(screen)}}</h1><p>{{remarks||t(def.desc)}}</p></div><div class="heading-icon"><i :class="def.icon"></i></div></div>
    <div v-if="canWrite||canDelete" class="batch-bar" :class="{'has-changes':dirty}"><div class="batch-status"><span class="save-indicator"><i :class="dirty?'las la-pen':'las la-check'"></i></span><div><strong>{{t(dirty?'pending':'clean')}}</strong><small v-if="dirty">{{t('new')}} {{changes.insert.length}} · {{t('changed')}} {{changes.update.length}} · {{t('removed')}} {{changes.delete.length}}</small><small v-else>{{t('batch_hint')}}</small></div></div><div class="batch-buttons"><button class="button" :disabled="!dirty||saving" @click="discard">{{t('discard')}}</button><button class="button is-primary" :disabled="!dirty||saving" @click="save()"><i class="las la-check"></i>{{saving?t('saving'):t('save')}}<span v-if="dirty" class="button-counter">{{changeCount}}</span></button></div></div>
    <form class="search-panel" @submit.prevent="search"><div class="panel-heading"><span><i class="las la-filter"></i> {{t('filters')}}</span><button type="button" class="text-button filter-toggle" @click="filtersOpen=!filtersOpen" :aria-expanded="filtersOpen"><i :class="filtersOpen?'las la-angle-up':'las la-angle-down'"></i></button></div><div class="search-fields" v-show="filtersOpen"><div class="field" v-for="key in def.filters" :key="key"><label class="label" :for="'search-'+key">{{t(key)}}</label><div v-if="key==='use'||key==='status'" class="select is-fullwidth"><select :id="'search-'+key" :value="filters[key] || ''" @input="filters[key]=$event.target.value"><option value="">{{t('all')}}</option><option v-for="option in key==='use'?['Y','N']:statuses" :key="option" :value="option">{{key==='use'?t(option==='Y'?'enabled':'disabled'):option}}</option></select></div><input v-else class="input" :id="'search-'+key" :value="filters[key] || ''" @input="filters[key]=$event.target.value" :placeholder="t(key)" autocomplete="off"></div><div class="search-actions"><button type="button" class="button" @click="resetFilters"><i class="las la-undo-alt"></i>{{t('reset')}}</button><button class="button is-primary" :disabled="loading"><i class="las la-search"></i>{{t('search')}}</button></div></div><div class="collapsed-search" v-if="!filtersOpen"><span>{{Object.values(filters).filter(Boolean).join(' · ')||t('all')}}</span><button class="button is-primary" :disabled="loading">{{t('search')}}</button></div></form>
    <div v-if="error" class="error-panel page-error" role="alert"><i class="las la-exclamation-circle"></i><span>{{error}}</span><button class="text-button" @click="dirty?save():runLoad()">{{t('retry')}}</button></div>
    <section class="list-panel" :aria-label="t(screen)+' '+t('list')" :aria-busy="loading">
     <div class="list-toolbar"><div class="list-title"><h2>{{t(screen)}} <span class="count-chip">{{displayTotal}}</span></h2><span v-if="selected.length" class="selection-count">{{selected.length}} {{t('selected')}}</span></div><div class="toolbar-actions"><button class="icon-button refresh-button" :aria-label="t('refresh')" @click="refresh" :disabled="loading"><i class="las la-sync-alt"></i></button><button v-if="canDelete" class="button danger-ghost" :disabled="!selected.length" @click="removeRows(selected)"><i class="las la-trash-alt"></i>{{t('delete')}}</button><button v-if="canWrite" class="button is-primary is-light" @click="editRow()"><i class="las la-plus"></i>{{t('add')}}</button></div></div>
     <div v-if="screen==='admcode'" class="code-breadcrumb"><button @click="goLevel(-1)" class="crumb-root"><i class="las la-folder-open"></i>{{t('root')}}</button><template v-for="(crumb,index) in trail" :key="crumb.id"><i class="las la-angle-right"></i><button @click="goLevel(index)">{{localeValue(crumb,state.locale)||crumb.code}}</button></template><button class="up-button" :disabled="!trail.length" @click="goLevel(trail.length-2)"><i class="las la-level-up-alt"></i>{{t('up')}}</button></div>
     <div class="grid-zone"><Grid ref="grid" :key="screen+'-'+state.locale" :rows="visibleRows" :columns="columns" :locale="state.locale" :title="t(screen)" :loading="loading" :inline-editing="['admcode','admmsge'].includes(screen)&&canWrite" :selectable="canDelete" @selection="selected=$event" @edit="cellEdited"/><div v-if="!loading&&!visibleRows.length" class="empty-state"><i class="las la-inbox"></i><h3>{{t('empty')}}</h3><p>{{t('empty_hint')}}</p><button v-if="canWrite" class="button" @click="editRow()"><i class="las la-plus"></i>{{t('add')}}</button></div></div>
     <div class="grid-footer"><span>{{t('total')}} <strong>{{displayTotal}}</strong> {{t('items')}}</span><div v-if="screen!=='admmenu'" class="pagination-controls"><label class="page-size"><select :value="pageSize" @change="setPageSize(+$event.target.value)" :aria-label="t('page_size')"><option :value="20">20 / {{t('page')}}</option><option :value="50">50 / {{t('page')}}</option><option :value="100">100 / {{t('page')}}</option><option :value="200">200 / {{t('page')}}</option></select></label><button class="icon-button" :disabled="page<=1||loading" @click="movePage(page-1)" :aria-label="t('previous')"><i class="las la-angle-left"></i></button><span class="current-page">{{page}}</span><span class="page-total">/ {{Math.max(1,pages)}}</span><button class="icon-button" :disabled="page>=pages||loading" @click="movePage(page+1)" :aria-label="t('next')"><i class="las la-angle-right"></i></button></div><span v-else class="muted">{{t('system')}}</span></div>
    </section>
    <div class="below-grid"><p><i class="las la-info-circle"></i><span class="desktop-hint">{{t(screen==='admcode'?'code_inline_hint':screen==='admmsge'?'message_inline_hint':'edit_hint')}}</span><span class="mobile-hint">{{t(screen==='admcode'?'code_inline_hint':screen==='admmsge'?'message_inline_hint':'mobile_hint')}}</span></p><span class="muted">kkdugi admin</span></div>

</div></template>
<script setup>
import {ref,computed,inject,onMounted,onBeforeUnmount,nextTick} from 'vue';
import Grid from '../components/Grid.vue';
import {MessageCellEditor} from '@js/grid/MessageCellEditor.mjs';
import {CodeCellEditor} from '@js/grid/CodeCellEditor.mjs';
const props=defineProps({screen:String,permissions:Object,menuId:String,title:String,remarks:String});
const s=inject('kkdugi');const {t,state,api,clone,payload,rowState,localeValue,validate,flatten,isProtected,prepareLocalizedRow,prepareBatch,menuDescendants,filterMenuRows,markMenuDeleted,restoreMenuRows,menuLabel}=s;
const definitions={admcode:{resource:'code',icon:'las la-layer-group',desc:'code_desc',batch:true,filters:['path','code','name','use']},admmsge:{resource:'i18n',icon:'las la-language',desc:'message_desc',batch:true,filters:['code','message']},admmenu:{resource:'menu',icon:'las la-sitemap',desc:'menu_desc',batch:true,filters:['name']}};
const def=computed(()=>definitions[props.screen]);
const canWrite=computed(()=>props.permissions?.['20']===true),canDelete=computed(()=>props.permissions?.['30']===true);
const filters=ref({}),applied=ref({}),rows=ref([]),baseline=ref([]),selected=ref([]),trail=ref([]),page=ref(1),pageSize=ref(20),pages=ref(1),total=ref(0),loading=ref(false),saving=ref(false),error=ref(''),tick=ref(0),grid=ref(null),filtersOpen=ref(true);
const displayTotal=computed(()=>props.screen==='admmenu'?rows.value.length:total.value);
const langs=computed(()=>s.languages);let generation=0,disposed=false,request;
const changes=computed(()=>{tick.value;return payload(rows.value,baseline.value);});
const changeCount=computed(()=>changes.value.insert.length+changes.value.update.length+changes.value.delete.length);
const dirty=computed(()=>changeCount.value>0),visibleRows=computed(()=>props.screen==='admmenu'?filterMenuRows(rows.value,applied.value.name,state.locale):rows.value);
const dialog={open:options=>s.dialog.open({...options,ownerId:props.menuId}),confirm:async options=>(await dialog.open({kind:'confirm',...options})).status==='submitted'};
function errorText(e){if(e.validationKey)return t(e.validationKey);if(e.errors?.length)return e.errors.map(item=>[item.id||item.code,item.message||s.message(item.reason||item.code)].filter(Boolean).join(': ')).join(' · ');return e.status!==undefined?s.errorText(e):e.message;}
async function load(){
 request?.abort();request=new AbortController();const id=++generation;loading.value=true;error.value='';selected.value=[];
 try{const result=await api.list(def.value.resource,{...applied.value,page:page.value,pageSize:pageSize.value,...(props.screen==='admcode'?{parentId:trail.value.at(-1)?.id||null}:{})},{signal:request.signal});if(id!==generation||disposed)return;
 rows.value=(props.screen==='admmenu'?flatten(result):result.contents).map(r=>({...r,_key:r.id||r.code}));baseline.value=clone(rows.value);total.value=props.screen==='admmenu'?rows.value.length:result.totalItems;pages.value=props.screen==='admmenu'?1:result.totalPages;
 if(page.value>Math.max(1,pages.value)){page.value=Math.max(1,pages.value);return await load();}
 }catch(e){if(e.name==='AbortError'||disposed)return;if(id===generation){error.value=errorText(e);throw e;}}finally{if(id===generation)loading.value=false;}
}
async function runLoad(){try{await load();}catch{}}
async function guard(){
 grid.value?.stop();tick.value++;if(saving.value||state.dialogs.some(d=>d.ownerId===props.menuId))return false;if(!dirty.value)return true;
 const result=await dialog.open({kind:'confirm',title:t('unsaved'),message:t('unsaved_hint'),submitLabel:t('save_leave'),onSubmit:()=>save(true),alternative:{label:t('discard_leave'),action:()=>{rows.value=clone(baseline.value);}}});return result.status==='submitted';
}
async function search(){if(props.screen==='admmenu'){applied.value=clone(filters.value);return;}if(await guard()){applied.value=clone(filters.value);page.value=1;await runLoad();}}
function resetFilters(){filters.value={};if(props.screen==='admmenu')applied.value={};}
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
 if(props.screen==='admcode'){
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
 }else if(props.screen==='admmenu'){
 list.push({headerName:t('label'),width:270,valueGetter:p=>menuLabel(p.data,state.locale),cellRenderer:p=>{const el=document.createElement('span');el.className='name-cell';el.style.paddingLeft=(p.data._depth||0)*18+'px';const icon=document.createElement('i');icon.className=p.data.program?'las la-window-maximize':'las la-folder';icon.setAttribute('aria-hidden','true');el.append(icon,document.createTextNode(' '+(p.value||'—')));return el;}},simple('program','program',160),{...simple('use','use',110),cellRenderer:p=>badge(t(p.value==='Y'?'enabled':'disabled'),p.value==='Y'?'active':'neutral')},{...simple('close','closable',120),cellRenderer:p=>badge(t(p.value==='Y'?'enabled':'disabled'),p.value==='Y'?'active':'neutral')},simple('sort','sort',95));
 }else{
 list.push({...simple('code','code',260),editable:p=>canWrite.value&&!p.data._deleted&&!baseline.value.some(r=>r._key===p.data._key),cellClass:'mono-cell'});
 for(const lang of langs.value)list.push({colId:'locale_'+lang.code,headerName:lang.label+' · '+lang.code,width:270,cellClass:'message-cell',valueGetter:p=>p.data.locale?.[lang.code]||'',valueSetter:p=>{if(!canWrite.value)return false;p.data.locale??={};p.data.locale[lang.code]=p.newValue;return true;},editable:p=>canWrite.value&&!p.data._deleted,cellEditor:MessageCellEditor,cellEditorPopup:false});
 }
 list.push({colId:'actions',headerName:t('actions'),headerClass:'grid-center-header',cellClass:'grid-center-cell',minWidth:190,width:props.screen==='admmenu'?300:props.screen==='admcode'?190:220,cellRenderer:p=>{const el=document.createElement('div');el.className='row-actions';if(p.data._deleted){if(canDelete.value)el.append(action(t('restore'),'las la-undo',()=>restore(p.data)));return el;}if(props.screen==='admcode'){if(p.data.id)el.append(action(t('children'),'las la-folder-open',()=>descend(p.data)));if(canDelete.value)el.append(action(t('delete'),'las la-trash-alt',()=>removeRows([p.data]),true)); }else if(props.screen==='admmenu'){
 if(canWrite.value){el.append(action(t('edit'),'las la-pen',()=>editRow(p.data)));if(!isProtected(p.data,rows.value))el.append(action(t('add_child'),'las la-plus',()=>editRow(null,p.data)));}
 if(canDelete.value&&!isProtected(p.data,rows.value))el.append(action(t('delete'),'las la-trash-alt',()=>removeRows([p.data]),true));
 }else if(canDelete.value)el.append(action(t('delete'),'las la-trash-alt',()=>removeRows([p.data]),true));return el;}});return list;
});
function cellEdited(event){
 if(props.screen==='admcode'&&!event.data.id&&event.colDef.field==='code')event.data.path=(trail.value.at(-1)?.path||'')+'/'+event.data.code;
 tick.value++;rows.value=rows.value.map(row=>row._key===event.data._key?{...row}:row);
}
function codeLanguages(row){const languages=[...langs.value];for(const code of Object.keys(row.locale||{}))if(!languages.some(l=>l.code===code))languages.push({code,label:code});return languages;}
function applyCodeFields(key,fields){
 if(disposed||!canWrite.value)return;const index=rows.value.findIndex(r=>r._key===key&&!r._deleted);if(index<0)return;
 rows.value[index]={...rows.value[index],...fields};rows.value=[...rows.value];tick.value++;
}
async function editCodeDetails(row){
 if(saving.value||loading.value||row._deleted)return;grid.value?.stop();
 const original=baseline.value.find(r=>r._key===row._key);
 await dialog.open({kind:'form',title:t('code_details'),subtitle:row.code||t('new'),batch:canWrite.value,readonly:!canWrite.value,initial:{locale:clone(row.locale||{})},translation:'name',languages:codeLanguages(row),validate:value=>{
  try{prepareLocalizedRow('code',{...row,locale:value.locale},original);return '';}catch(e){return errorText(e);}
 },onSubmit:value=>applyCodeFields(row._key,{locale:prepareLocalizedRow('code',{...row,locale:value.locale},original).locale})});
}
async function editCodeExtras(row){
 if(saving.value||loading.value||row._deleted)return;grid.value?.stop();
 const initial=clone(Object.fromEntries([1,2,3,4,5].map(n=>['extra'+n,row['extra'+n]])));
 await dialog.open({kind:'form',title:t('extra'),subtitle:row.code||t('new'),batch:canWrite.value,readonly:!canWrite.value,initial,extraTabs:true,onSubmit:value=>applyCodeFields(row._key,value)});
}
function newRow(parent){
 const locale=Object.fromEntries(langs.value.map(l=>[l.code,props.screen==='admmsge'?'':{[props.screen==='admmenu'?'label':'name']:'',remarks:''}]));
 if(props.screen==='admmsge')return {code:'',locale};
 if(props.screen==='admmenu')return {id:'',parentId:parent?.id||null,program:'',icon:'las la-folder',locale,use:'Y',close:'Y',sort:0,_depth:parent?(parent._depth||0)+1:0};
 return {id:'',parentId:trail.value.at(-1)?.id||null,code:'',locale,use:'Y',path:trail.value.at(-1)?.path||'',level:trail.value.length,sort:0,extra1:'',extra2:'',extra3:'',extra4:'',extra5:''};
}
async function editRow(row=null,parent=null){
 if(!canWrite.value||saving.value||loading.value)return;
 if(['admcode','admmsge'].includes(props.screen)){grid.value?.stop();rows.value=[{...newRow(),_key:crypto.randomUUID()},...rows.value];tick.value++;await nextTick();grid.value?.edit(0,'code');return;}
 if(parent&&isProtected(parent,rows.value))return;
 if(parent&&!parent.id){s.notify('menu_parent_pending');return;}
 const menu=props.screen==='admmenu',initial=row?clone(row):newRow(parent),fixed=menu&&row&&isProtected(row,rows.value);
 const fields=[];const add=(path,label,type='text',extra={})=>fields.push({path,label:t(label),type,...extra});
 if(menu){
  initial._parentLabel=menuLabel(rows.value.find(r=>r.id&&r.id===initial.parentId),state.locale)||t('root');
  add('_parentLabel','parent','text',{readonly:true});add('program','program','text',{readonly:fixed,hint:t('program_hint'),placeholder:'admin/menu'});add('icon','icon','text');
 }
 add('use','use','select',{readonly:fixed,options:[{value:'Y',label:t('enabled')},{value:'N',label:t('disabled')}]});
 if(menu)add('close','closable','select',{readonly:fixed,options:[{value:'Y',label:t('enabled')},{value:'N',label:t('disabled')}]});
 add('sort','sort','number',{readonly:fixed});
 const original=row?baseline.value.find(r=>r._key===row._key):undefined;
 const languages=[...langs.value];for(const code of Object.keys(initial.locale||{}))if(!languages.some(l=>l.code===code))languages.push({code,label:code});
 await dialog.open({kind:'form',title:t(row?'edit':'add')+' · '+(props.title||t(props.screen)),subtitle:row?.code||row?.program,batch:true,wide:true,initial,fields,translation:'label',languages,validate:value=>{
  try{const e=validate(def.value.resource,prepareLocalizedRow(def.value.resource,value,original),langs.value);return e?t(e):'';}catch(e){return errorText(e);}
 },onSubmit:value=>{
  if(disposed||!canWrite.value)return;
  const clean=prepareLocalizedRow(def.value.resource,value,original);Object.assign(value,{locale:clean.locale,parentId:clean.parentId});
  if(!row&&!menu)value.path=(trail.value.at(-1)?.path||'')+'/'+value.code;value._key=row?._key||crypto.randomUUID();
  if(row)rows.value.splice(rows.value.findIndex(r=>r._key===row._key),1,value);
  else if(menu&&parent){const descendants=menuDescendants(rows.value,[parent]);const index=Math.max(...descendants.map(r=>rows.value.indexOf(r)));rows.value.splice(index+1,0,value);}
  else rows.value.unshift(value);
  rows.value=[...rows.value];tick.value++;
 }});
}
async function removeRows(targets){
 if(!canDelete.value||!targets.length||saving.value||loading.value)return;const menu=props.screen==='admmenu';
 if(menu&&targets.some(r=>isProtected(r,rows.value))){await dialog.open({kind:'alert',title:t('notice'),message:t('protected')});return;}
 const ok=await dialog.confirm({title:t('confirm_delete'),message:targets.map(r=>menu?localeValue(r,state.locale,'label'):r.code).join(', '),description:t(props.screen==='admmsge'?'permanent':'cascade'),danger:true,submitLabel:t('delete')});if(!ok||disposed)return;
 const group=crypto.randomUUID();if(menu)markMenuDeleted(rows.value,targets,group);else for(const row of targets)if(!row._deleted){row._deleted=true;row._deleteGroup=group;}
 rows.value=rows.value.filter(r=>!(r._deleted&&!baseline.value.some(b=>b._key===r._key)));selected.value=[];grid.value?.clear();tick.value++;
}
function restore(row){if(!canDelete.value||saving.value||loading.value)return;restoreMenuRows(rows.value,row);rows.value=[...rows.value];tick.value++;}
async function discard(){if(await dialog.confirm({title:t('discard'),message:t('discard_form')})){rows.value=clone(baseline.value);selected.value=[];tick.value++;}}
async function save(throwError=false){
 if(saving.value||loading.value)return;grid.value?.stop();tick.value++;let batch=changes.value;if(!changeCount.value)return;saving.value=true;error.value='';
 try{
 if((!canWrite.value&&(batch.insert.length||batch.update.length))||(!canDelete.value&&batch.delete.length))throw Error(t('forbidden'));
 if(props.screen==='admmsge'){const codes=rows.value.filter(r=>!r._deleted).map(r=>r.code);if(new Set(codes).size!==codes.length)throw Error(t('duplicate'));}
 batch=prepareBatch(def.value.resource,batch,baseline.value);
 for(const row of [...batch.insert,...batch.update]){const e=validate(def.value.resource,row,langs.value);if(e)throw Error(t(e));}
 await api.persist(def.value.resource,batch);if(disposed)return;baseline.value=clone(rows.value.filter(r=>!r._deleted));rows.value=clone(baseline.value);tick.value++;s.notify(props.screen==='admmenu'?'menu_saved':'success');
 try{await load();}catch{rows.value=[];baseline.value=[];total.value=0;error.value=t('saved_reload');}
 }catch(e){if(!disposed)error.value=errorText(e);if(throwError)throw e;}finally{saving.value=false;}
}
function beforeUnload(e){if(dirty.value||state.dialogs.some(d=>d.ownerId===props.menuId)){e.preventDefault();e.returnValue='';}}
defineExpose({beforeLeave:guard});
onMounted(()=>{runLoad();window.addEventListener('beforeunload',beforeUnload);});
onBeforeUnmount(()=>{disposed=true;generation++;request?.abort();s.cancelDialogs(props.menuId);window.removeEventListener('beforeunload',beforeUnload);});
</script>
