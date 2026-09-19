// Bearer tokens are scoped to this browser tab. Credentials are never persisted.
const key='kkdugi.admin.token';
export function getToken(){try{return sessionStorage.getItem(key)||'';}catch{return '';}}
export function saveToken(token){if(typeof token!=='string'||!token)throw Error('Missing token');sessionStorage.setItem(key,token);}
export function clearToken(){try{sessionStorage.removeItem(key);}catch{}}
export function loginUrl(basePath=''){return basePath.replace(/\/$/,'')+'/login';}
