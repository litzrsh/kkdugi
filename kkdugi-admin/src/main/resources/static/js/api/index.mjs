// Only endpoints documented in docs/api are exposed by the live adapter.
import {createHttp,ApiError} from './http.mjs';
import {SHELL_MENU_ID,validateMenuId} from './request-context.mjs';
export {ApiError};
export function createApi(config,transport=fetch){
 const http=createHttp(config,transport);
 const resourcePath=resource=>{if(!['code','i18n','menu'].includes(resource))throw new Error('Unsupported API resource');return '/api/v1.0/admin/'+resource;};
 function scoped(menuId){
  // Capture the originating page once, including requests made by its dialog callbacks.
  const context=options=>menuId===undefined?options:{...options,menuId};
  const authorityPath=id=>'/api/v1.0/admin/authority'+(id===undefined?'':'/'+encodeURIComponent(id));
  const userPath=id=>'/api/v1.0/admin/user'+(id===undefined?'':'/'+encodeURIComponent(id));
  return {
   user:{
    list:(params,options)=>http.json(userPath(),'POST',params,context(options)),
    detail:(id,options)=>http.json(userPath(id),'GET',undefined,context(options)),
    create:(body,options)=>http.json(userPath()+'/regist','POST',body,context(options)),
    save:(id,body,options)=>http.json(userPath(id),'POST',body,context(options)),
    remove:(id,options)=>http.json(userPath(id)+'/delete','POST',undefined,context(options)),
    resetPassword:(ids,options)=>http.json(userPath()+'/reset-password','POST',{id:ids},context(options)),
    changeStatus:(ids,status,options)=>http.json(userPath()+'/change-status','POST',{id:ids,status},context(options)),
    authorities:(id,options)=>http.json(userPath(id)+'/authorities','GET',undefined,context(options)),
    saveAuthorities:(id,body,options)=>http.json(userPath(id)+'/authorities','POST',body,context(options)),
    authorityCandidates:(id,query,options)=>http.json(userPath(id)+'/authority-candidates','POST',{query},context(options))
   },
   authority:{
    list:(params,options)=>http.json(authorityPath(),'POST',params,context(options)),
    detail:(id,options)=>http.json(authorityPath(id),'GET',undefined,context(options)),
    create:(body,options)=>http.json(authorityPath()+'/regist','POST',body,context(options)),
    save:(id,body,options)=>http.json(authorityPath(id),'POST',body,context(options)),
    remove:(id,options)=>http.json(authorityPath(id)+'/delete','POST',undefined,context(options)),
    users:(id,query,options)=>http.json(authorityPath(id)+'/user','POST',{query},context(options)),
    menus:(id,options)=>http.json(authorityPath(id)+'/menu','POST',undefined,context(options))
   },
   forMenu:id=>{validateMenuId(id);if(id===SHELL_MENU_ID)throw new Error('Shell context is reserved');return scoped(id);},
   request:(path,method,body,options)=>http.json(path,method,body,context(options)),
   codes:(path,{enum:enumCode=false,children=false,locale,...options}={})=>http.json('/api/v1.0/code?'+new URLSearchParams({path,...(enumCode?{enum:'true'}:{}),...(children?{children:'true'}:{}),...(locale?{lang:locale}:{})}),'GET',undefined,context(options)),
   menus:({locale,...options}={})=>http.json('/api/v1.0/menu'+(locale?'?'+new URLSearchParams({lang:locale}):''),'GET',undefined,context({menuId:SHELL_MENU_ID,...options})),
   pragma:(id,options)=>http.text('/pragma/'+encodeURIComponent(id),{...options,menuId:id}),
   list:(resource,params,options)=>http.json(resourcePath(resource),resource==='menu'?'GET':'POST',resource==='menu'?undefined:params,context(options)),
   persist:(resource,changes,options)=>http.json(resourcePath(resource)+'/persist','POST',changes,context(options))
  };
 }
 return scoped();
}
