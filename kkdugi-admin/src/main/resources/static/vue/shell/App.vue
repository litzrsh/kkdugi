<template>
 <div class="workspace-shell">
  <div v-if="mobileNav" class="nav-shade" @click="mobileNav=false"></div>
  <aside ref="sidebar" class="sidebar" :class="{'is-open':mobileNav}"><a href="#" class="brand" @click.prevent="navigate(null)"><span class="brand-mark"><b></b><b></b><b></b><b></b></span><span>kkdugi<span class="brand-dot">.</span></span><small>ADMIN</small></a><div class="workspace-label"><span class="workspace-icon"><i class="las la-cube"></i></span><div><strong>{{t('workspace')}}</strong><small>System workspace</small></div></div><button class="icon-button sidebar-close mobile-toggle" @click="mobileNav=false" :aria-label="t('close')"><i class="las la-times"></i></button><div class="nav-heading">{{t('system')}}</div><nav :aria-label="t('system')"><NavigationTree :nodes="menus" :active-id="active?.id" @select="navigate"/></nav><p v-if="menuLoading" class="nav-status">{{t('loading')}}</p><div v-if="menuError" class="nav-status" role="alert">{{menuError}}<button class="text-button" @click="loadMenus">{{t('retry')}}</button></div><p v-if="!menuLoading&&!menuError&&!menus.length" class="nav-status">{{t('no_menus')}}</p><div class="sidebar-bottom"><div class="side-note"><i class="las la-shapes"></i><span>{{t('start_here')}}</span></div><div class="side-version"><span><i class="las la-sun"></i> {{t('light')}}</span><span>v0.1</span></div></div></aside>
  <div class="main-shell" :inert="mobileNav || undefined"><header class="topbar"><div class="topbar-left"><button class="icon-button mobile-toggle" @click="mobileNav=!mobileNav" :aria-label="t('menu')" :aria-expanded="mobileNav"><i class="las la-bars"></i></button><span class="topbar-section">{{t('system')}}</span><span class="breadcrumb-separator">/</span><span>{{active?.title||t('select_screen')}}</span></div><div class="topbar-right"><div class="locale-select"><i class="las la-globe"></i><select :value="state.locale" @change="changeLocale($event.target.value)" :aria-label="t('language')"><option v-for="lang in s.languages" :key="lang.code" :value="lang.code">{{lang.label}}</option></select></div><span class="topbar-divider"></span><div class="operator"><button class="icon-button" :aria-label="t('logout')" :title="t('logout')" @click="logout"><i class="las la-sign-out-alt"></i></button><span class="avatar">A</span><span>{{t('operator')}}</span></div></div></header>
   <main id="main-content" class="main-content" :aria-busy="loading">
    <div v-if="screenError" class="error-panel page-error" role="alert"><span>{{screenError}}</span><button class="text-button" @click="retry">{{t('retry')}}</button></div>
    <div v-if="loading" class="screen-loading" role="status">{{t('loading')}}</div>
    <div :inert="loading || undefined"><PageHost v-if="component" :key="active.id" ref="host" :component="component" :menu="active" :locale="state.locale"/></div>
    <section v-if="!component&&!loading" class="empty-state"><i class="las la-th-large"></i><h1 tabindex="-1">{{t('select_screen')}}</h1><p>{{t('select_screen_hint')}}</p><button class="button" @click="loadMenus">{{t('refresh')}}</button></section>
   </main>
  </div>
  <DialogHost/><div v-if="state.notice" class="toast" role="status">{{state.notice}}</div>
 </div>
</template>
<script setup>
import {ref,shallowRef,inject,onMounted,onBeforeUnmount,watch,nextTick} from 'vue';
import NavigationTree from './NavigationTree.vue';
import PageHost from './PageHost.vue';
import DialogHost from '../components/DialogHost.vue';
const s=inject('kkdugi'),{t,state}=s;
const {normalizeMenus,findMenu,menuHash,menuIdFromHash,createPageRequests}=s.navigation;
const menus=ref([]),menuLoading=ref(false),menuError=ref(''),screenError=ref(''),loading=ref(false),mobileNav=ref(false),sidebar=ref(null),host=ref(null),active=ref(null),component=shallowRef(null);
const requests=createPageRequests(s.runtime.page);let disposed=false,menuRequest,committedHash='',pendingMenu=null,transition=0,guardPromise;
async function guard(reason){if(guardPromise)return guardPromise;guardPromise=Promise.resolve(host.value?.beforeLeave(reason)??true);try{return await guardPromise;}finally{guardPromise=null;}}
function restoreHash(){history.replaceState(null,'',location.pathname+location.search+committedHash);}
async function navigate(menu,fromHistory=false){
 const ticket=++transition;requests.cancel();loading.value=false;
 if(menu?.id===active.value?.id){restoreHash();mobileNav.value=false;return;}
 if(!await guard('navigation')){restoreHash();return;}if(ticket!==transition||disposed)return;
 pendingMenu=menu;screenError.value='';
 if(!menu){component.value=null;active.value=null;committedHash='';if(!fromHistory)history.pushState(null,'',location.pathname+location.search);mobileNav.value=false;return;}
 loading.value=true;
 try{const result=await requests.open(menu.id);if(!result||disposed||ticket!==transition)return;component.value=result.component;active.value=menu;committedHash=menuHash(menu.id);if(fromHistory)restoreHash();else history.pushState(null,'',committedHash);mobileNav.value=false;await nextTick();document.querySelector('#main-content h1')?.focus();}
 catch(e){if(ticket===transition&&!disposed){screenError.value=s.errorText(e);restoreHash();}}
 finally{if(ticket===transition)loading.value=false;}
}
async function readHash(){const id=menuIdFromHash(location.hash);if(id===null){screenError.value=t('not_found');restoreHash();return;}if(!id){await navigate(null,true);return;}const menu=findMenu(menus.value,id);if(!menu||menu.children.length){screenError.value=t('not_found');restoreHash();return;}await navigate(menu,true);}
async function loadMenus(){
 menuRequest?.abort();const controller=new AbortController();menuRequest=controller;menuLoading.value=true;menuError.value='';
 try{const result=normalizeMenus(await s.api.menus({signal:controller.signal}));if(disposed||controller!==menuRequest)return;menus.value=result;await readHash();}
 catch(e){if(e.name!=='AbortError'&&!disposed)menuError.value=s.errorText(e);}
 finally{if(controller===menuRequest)menuLoading.value=false;}
}
function retry(){if(pendingMenu)navigate(pendingMenu);else loadMenus();}
async function logout(){if(!await guard('logout'))return;try{await s.logout();}catch(e){screenError.value=s.errorText(e);}}
async function changeLocale(locale){if(!await guard('locale'))return;const url=new URL(location.href);url.searchParams.set('lang',locale);location.assign(url.href);}
function closeSession(){transition++;requests.cancel();menuRequest?.abort();component.value=null;active.value=null;s.cancelDialogs();}
function keydown(e){if(e.key==='Escape')mobileNav.value=false;if(mobileNav.value&&e.key==='Tab'){const nodes=[...sidebar.value.querySelectorAll('a,button')].filter(n=>n.offsetParent!==null);const first=nodes[0],last=nodes.at(-1);if(e.shiftKey&&document.activeElement===first){e.preventDefault();last?.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first?.focus();}}}
watch(mobileNav,async open=>{await nextTick();if(open)sidebar.value?.querySelector('button')?.focus();else document.querySelector('.topbar .mobile-toggle')?.focus();});
onMounted(()=>{loadMenus();window.addEventListener('hashchange',readHash);window.addEventListener('keydown',keydown);window.addEventListener('kkdugi:unauthorized',closeSession);});
onBeforeUnmount(()=>{disposed=true;closeSession();window.removeEventListener('hashchange',readHash);window.removeEventListener('keydown',keydown);window.removeEventListener('kkdugi:unauthorized',closeSession);});
</script>
