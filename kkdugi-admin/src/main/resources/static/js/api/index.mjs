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
  return {
   forMenu:id=>{validateMenuId(id);if(id===SHELL_MENU_ID)throw new Error('Shell context is reserved');return scoped(id);},
   request:(path,method,body,options)=>http.json(path,method,body,context(options)),
   codes:(path,options)=>http.json('/api/v1.0/code?'+new URLSearchParams({path}),'GET',undefined,context(options)),
   menus:options=>http.json('/api/v1.0/menu','GET',undefined,context({menuId:SHELL_MENU_ID,...options})),
   pragma:(id,options)=>http.text('/pragma/'+encodeURIComponent(id),{...options,menuId:id}),
   list:(resource,params,options)=>http.json(resourcePath(resource),resource==='menu'?'GET':'POST',resource==='menu'?undefined:params,context(options)),
   persist:(resource,changes,options)=>http.json(resourcePath(resource)+'/persist','POST',changes,context(options))
  };
 }
 return scoped();
}
