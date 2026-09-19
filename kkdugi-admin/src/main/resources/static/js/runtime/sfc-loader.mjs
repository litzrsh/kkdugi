export function createSfcRuntime({basePath='',vue,loader,api,transport=fetch,origin=location.origin}){
 const base=basePath.replace(/\/$/,'');const shared={vue};
 const isStatic=path=>path.startsWith(base+'/vue/')||path.startsWith(base+'/js/');
 function resolve({refPath,relPath}){
  if(relPath==='vue')return 'vue';
  const path=relPath.startsWith('@vue/')?base+'/vue/'+relPath.slice(5):relPath.startsWith('@js/')?base+'/js/'+relPath.slice(4):relPath;
  const url=new URL(path,new URL(refPath||base+'/vue/',origin));
  if(url.origin!==origin||(!isStatic(url.pathname)&&!url.pathname.startsWith(base+'/__pragma__/'))||url.search||url.hash)throw new Error('Unsupported SFC dependency');
  return url.pathname;
 }
 async function compile(entry,source,signal){
  const cache={...shared};
  const component=await loader.loadModule(entry,{
   moduleCache:cache,pathResolve:resolve,
   getFile:async path=>{
    if(path===entry&&source!==undefined)return source;
    if(!isStatic(path))throw new Error('Unsupported SFC source');
    const response=await transport(path,{credentials:'same-origin',redirect:'error',signal});
    if(!response.ok)throw new Error('SFC dependency unavailable');return response.text();
   },addStyle:()=>{throw new Error('Use shared css/app.css');}
  });
  if(signal?.aborted)throw new DOMException('Aborted','AbortError');
  for(const [key,value] of Object.entries(cache))if(key==='vue'||isStatic(key))shared[key]=value;
  return component;
 }
 return {shell:()=>compile(base+'/vue/shell/App.vue'),page:async(id,signal)=>compile(base+'/__pragma__/entry.vue',await api.pragma(id,{signal}),signal)};
}
