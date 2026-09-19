import {getToken,clearToken,loginUrl} from '../auth/session.mjs';
export class ApiError extends Error{
 constructor(status,body){super(body?.message||`HTTP ${status}`);this.status=status;this.body=body;this.errors=Array.isArray(body)?body:body?.errors||[];}
}
export function createHttp(config={},transport=fetch){
 const base=(config.basePath||'').replace(/\/$/,'');
 async function send(path,method,body,options={},textMode=false){
  if(!/^\/(?:api\/v1\.0\/(?:(?:admin|auth)\/|menu(?:$|\?))|pragma\/)/.test(path)||path.includes('..')||path.includes('\\'))throw new Error('Invalid API path');
  const headers={Accept:'application/json'};
  const token=getToken();if(token)headers.Authorization='Bearer '+token;
  if(body!==undefined)headers['Content-Type']='application/json';
  if(config.csrf?.header&&config.csrf?.token)headers[config.csrf.header]=config.csrf.token;
  let response;
  try{response=await transport(base+path,{method,headers,credentials:'same-origin',redirect:'error',cache:'no-store',signal:options.signal,...(body===undefined?{}:{body:JSON.stringify(body)})});}
  catch(e){if(e.name==='AbortError')throw e;throw new ApiError(0,{message:'network'});}
  if(response.status===401){clearToken();config.onUnauthorized?.();location.replace(loginUrl(base));throw new ApiError(401);}
  const raw=await response.text();let value=null;
  if(!response.ok){try{value=raw?JSON.parse(raw):null;}catch{}throw new ApiError(response.status,value);}
  if(textMode){
   if(!response.headers.get('content-type')?.includes('text/html')||!/<template[\s>]/i.test(raw)||/<(?:!doctype|html|body)[\s>]/i.test(raw))throw new ApiError(502,{message:'Invalid screen response'});
   return raw;
  }
  try{return raw?JSON.parse(raw):null;}catch{throw new ApiError(502,{message:'Invalid JSON response'});}
 }
 return {json:(path,method='GET',body,options)=>send(path,method,body,options),text:(path,options)=>send(path,'GET',undefined,options,true)};
}
