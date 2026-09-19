// The login response sets the token cookie (name comes from adminUiConfig.tokenCookie); the API layer
// reads it here and sends it as a Bearer header. Credentials are never persisted by the client.
const cookieName=()=>globalThis.window?.KKDUGI?.tokenCookie||'KKDUGI_TOKEN';
const cookiePath=()=>((globalThis.window?.KKDUGI?.basePath||'').replace(/\/$/,'')+'/');
export function getToken(){
 try{
  const prefix=cookieName()+'=';
  for(const part of document.cookie.split(';')){const entry=part.trim();if(entry.startsWith(prefix))return decodeURIComponent(entry.slice(prefix.length));}
 }catch{}
 return '';
}
// The server clears the cookie on logout; this covers a 401 (expired/invalid session) where no logout ran.
export function clearToken(){try{document.cookie=cookieName()+'=; Max-Age=0; path='+cookiePath()+'; SameSite=Lax';}catch{}}
export function loginUrl(basePath=''){return basePath.replace(/\/$/,'')+'/login';}
