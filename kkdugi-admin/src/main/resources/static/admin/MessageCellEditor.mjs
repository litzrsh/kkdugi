// Single-line in-cell input. Enter commits; Escape cancels through AG Grid.
export class MessageCellEditor {
 init(params) {
  this.input=document.createElement('input');
  this.input.type='text';
  this.input.className='message-cell-editor';
  this.input.value=params.value??'';
  this.originalValue=params.value??'';
  this.initialInputValue=this.input.value;
  this.input.setAttribute('aria-label',params.column.getColDef().headerName);
  this.input.addEventListener('keydown',event=>{
   if(event.key==='Enter'){
    event.stopPropagation();
    event.preventDefault();params.stopEditing();
   }
  });
 }
 getGui(){return this.input;}
 afterGuiAttached(){this.input.focus();}
 getValue(){return this.input.value===this.initialInputValue?this.originalValue:this.input.value;}
 isPopup(){return false;}
}
