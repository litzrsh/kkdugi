// Only endpoints documented in docs/api are exposed by the live adapter.
import {createHttp,ApiError} from './http.mjs';
export {ApiError};
export function createApi(config,transport=fetch){
 const http=createHttp(config,transport);
 const resourcePath=resource=>{if(!['code','i18n','menu'].includes(resource))throw new Error('Unsupported API resource');return '/api/v1.0/admin/'+resource;};
 return {request:http.json,menus:options=>http.json('/api/v1.0/session/menu','GET',undefined,options),pragma:(id,options)=>http.text('/pragma/'+encodeURIComponent(id),options),list:(resource,params,options)=>http.json(resourcePath(resource),resource==='menu'?'GET':'POST',resource==='menu'?undefined:params,options),persist:(resource,changes,options)=>http.json(resourcePath(resource)+'/persist','POST',changes,options)};
}
