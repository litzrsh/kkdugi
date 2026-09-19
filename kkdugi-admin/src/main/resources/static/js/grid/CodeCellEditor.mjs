// Native inputs keep keyboard and mobile editing consistent across code columns.
export class CodeCellEditor {
 init(params){
  const select=params.kind==='select';
  this.input=document.createElement(select?'select':'input');
  this.input.className='code-cell-editor';
  this.input.setAttribute('aria-label',params.column.getColDef().headerName);
  this.kind=params.kind||'text';
  if(select){
   for(const item of params.options){const option=document.createElement('option');option.value=item.value;option.textContent=item.label;this.input.append(option);}
   this.input.addEventListener('change',()=>params.stopEditing());
  }else{
   this.input.type=this.kind==='number'?'number':'text';
   this.input.autocomplete='off';
   if(this.kind==='number'){this.input.min='0';this.input.step='1';this.input.inputMode='numeric';}
  }
  this.input.value=params.value??'';
  if(!select&&params.eventKey?.length===1)this.input.value=params.eventKey;
  this.input.addEventListener('keydown',event=>{
   if(event.key==='Enter'&&!event.isComposing){event.preventDefault();event.stopPropagation();params.stopEditing();}
  });
 }
 getGui(){return this.input;}
 afterGuiAttached(){this.input.focus();if(this.kind!=='select')this.input.select();}
 getValue(){return this.kind==='number'?(this.input.value===''?null:Number(this.input.value)):this.input.value;}
 isPopup(){return false;}
}
