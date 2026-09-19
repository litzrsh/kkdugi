// Request metadata only; the server must independently authorize the session and endpoint.
export const MENU_ID_HEADER='X-Menu-Id';
export const SHELL_MENU_ID='__shell__';
export function validateMenuId(id){
 if(typeof id!=='string'||!id.length||id.length>60||id!==id.trim()||/[^\x20-\x7e]/.test(id))throw new Error('A valid menu ID is required for this request');
 return id;
}
export function requestMenuId(path,method,id){
 const pathname=path.split('?')[0];
 if(['/api/v1.0/auth/login','/api/v1.0/auth/logout'].includes(pathname))return null;
 validateMenuId(id);
 if(id===SHELL_MENU_ID&&(pathname!=='/api/v1.0/menu'||method!=='GET'))throw new Error('Shell context is only valid for the menu tree request');
 if(pathname.startsWith('/pragma/')&&decodeURIComponent(pathname.slice('/pragma/'.length))!==id)throw new Error('Pragma menu ID must match the requested screen');
 return id;
}
