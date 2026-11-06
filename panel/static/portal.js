'use strict';
const $=s=>document.querySelector(s);
let credentials=null,account=null;
const message=text=>{$('#message').textContent=text;};
async function request(path,method='GET',data){
 const response=await fetch('/portal/api/'+path,{method,cache:'no-store',credentials:'omit',
 headers:{'Content-Type':'application/json','X-Portal-Request':'1','X-Device-Mac':credentials.mac,'X-Device-Key':credentials.key},
 ...(data!==undefined?{body:JSON.stringify(data)}:{})});
 if(!response.ok){let error;try{error=(await response.json()).detail}catch{}
 throw Error(response.status===429?'محاولات كثيرة. انتظر دقيقة ثم أعد المحاولة.':
 response.status===403?'PIN غير صحيح أو بيانات الجهاز غير صحيحة.':
 typeof error==='string'?error:'تعذّر إتمام العملية ('+response.status+')');}
 return response.json();
}
function showAccount(data){
 account=data;$('#login').hidden=true;$('#account').hidden=false;$('#playlist').hidden=true;
 const plan=data.plan==='trial'?'تجربة مجانية':data.expires_at?'تفعيل مؤقت':'مدى الحياة';
 $('#subscription').textContent=data.active?plan+' — '+(data.expires_at?'صالح حتى '+data.expires_at:'بدون تاريخ انتهاء'):'التفعيل منتهٍ أو موقوف — تواصل مع المدير';
 $('#playlist-view').textContent=data.playlist?data.playlist.name+' — '+data.playlist.url:'لا توجد قائمة شخصية حتى الآن';
 $('#edit').textContent=data.playlist?'تعديل قائمتي':'إضافة قائمة';
}
function openEdit(){
 const p=account.playlist;$('#playlist').hidden=false;
 $('#playlist').elements.name.value=p?.name||'';
 $('#playlist').elements.url.value=p?.url||'';
 $('#playlist').elements.pin.value='';
 $('#edit-title').textContent=p?'تعديل القائمة':'إضافة قائمة شخصية';
 $('#pin-label').textContent=p?.pin_set?'PIN القائمة الحالي':'تعيين PIN جديد للقائمة (4–8 أرقام)';
 $('#delete').hidden=!p||!p.pin_set;
}
async function busy(form,action){const buttons=[...form.querySelectorAll('button')];buttons.forEach(b=>b.disabled=true);try{await action();}catch(e){message(e.message);}finally{buttons.forEach(b=>b.disabled=false);}}
$('#login').onsubmit=e=>{e.preventDefault();busy(e.target,async()=>{
 credentials={mac:e.target.elements.mac.value.trim().toUpperCase(),key:e.target.elements.key.value.trim()};
 try{const data=await request('account');showAccount(data);e.target.reset();message('');}
 catch(error){credentials=null;throw error;}
});};
$('#edit').onclick=openEdit;
$('#cancel').onclick=()=>{$('#playlist').hidden=true;message('');};
$('#playlist').onsubmit=e=>{e.preventDefault();busy(e.target,async()=>{
 await request('playlist','PUT',{name:e.target.elements.name.value.trim(),
 url:e.target.elements.url.value.trim(),pin:e.target.elements.pin.value.trim()});
 showAccount(await request('account'));message('تم حفظ القائمة. لا تحتاج PIN لفتحها في التطبيق.');
});};
$('#delete').onclick=()=>{if(confirm('حذف قائمتك نهائيًا؟'))busy($('#playlist'),async()=>{
 await request('playlist','DELETE',{pin:$('#playlist').elements.pin.value.trim()});
 showAccount(await request('account'));message('تم حذف القائمة.');
});};
$('#logout').onclick=()=>{credentials=null;account=null;$('#playlist').reset();$('#account').hidden=true;$('#login').hidden=false;message('');};
