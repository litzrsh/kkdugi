// Form request: failures are JSON; success sets a cookie and redirects to the workspace.
export async function requestLogin(action,fields,{transport=fetch,signal}={}){
 const endpoint=new URL(action);
 const suffix='/api/v1.0/auth/login';
 if(!endpoint.pathname.endsWith(suffix))throw new Error('Invalid login endpoint');
 const base=endpoint.pathname.slice(0,-suffix.length);
 const response=await transport(endpoint.href,{method:'POST',headers:{Accept:'text/html'},body:new URLSearchParams(fields),credentials:'same-origin',redirect:'follow',cache:'no-store',signal});
 if(response.status===401){
  if(!response.headers.get('content-type')?.includes('application/json'))throw new Error('Invalid login error');
  const body=await response.json();
  if(typeof body?.code!=='string')throw new Error('Invalid login error');
  const reason={'auth.err.pending':'pending','auth.err.dormant':'dormant','auth.err.resigned':'resigned','auth.err.suspended':'suspended','auth.err.user_unavailable':'unavailable'}[body.code];
  if(reason)return {status:'blocked',reason};
  const status={'auth.err.password_required':'password_required','auth.err.password_expired':'password_expired','auth.err.password_invalid':'password_invalid'}[body.code];
  if(status)return {status};
  return {status:body.code==='session.err.duplicate'?'duplicate':'error'};
 }
 const target=new URL(response.url);
 await response.body?.cancel();
 if(!response.ok||!response.redirected||target.origin!==endpoint.origin)throw new Error('Unexpected login response');
 if(target.pathname===base+'/'&&!target.search&&!target.hash)return {status:'success',url:target.href};
 throw new Error('Unexpected login redirect');
}
