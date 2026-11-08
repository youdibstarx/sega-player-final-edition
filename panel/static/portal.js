'use strict';
const $=s=>document.querySelector(s);
let credentials=null,account=null;
const message=text=>{$('#message').textContent=text;};
const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function formatMac(value){
 const hex=String(value||'').replace(/[^0-9a-f]/gi,'').slice(0,12).toUpperCase();
 return hex.match(/.{1,2}/g)?.join(':')||'';
}
function bindMacInput(input){
 if(!input)return;
 input.addEventListener('input',()=>{const pos=input.selectionStart;const before=input.value.slice(0,pos);const formatted=formatMac(input.value);const next=Math.min(formatted.length,formatMac(before).length);input.value=formatted;try{input.setSelectionRange(next,next)}catch{}});
 input.addEventListener('blur',()=>{input.value=formatMac(input.value);});
}
bindMacInput($('#login').elements.mac);
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
 const lists=data.playlists|| (data.playlist?[data.playlist]:[]);
 $('#playlist-list').innerHTML=lists.length?lists.map(p=>'<article class="playlist-item"><strong>'+esc(p.name)+'</strong><p dir="ltr">'+esc(p.url)+'</p><div class="actions"><button type="button" data-edit-playlist="'+Number(p.id)+'">تعديل</button><button type="button" class="secondary" data-delete-playlist="'+Number(p.id)+'">حذف</button><span class="hint">'+(p.pin_set?'محمي بـ PIN':'عيّن PIN عند أول تعديل')+'</span></div></article>').join(''):'<p>لا توجد قوائم شخصية بعد.</p>';
 $('#playlist-list').querySelectorAll('[data-edit-playlist]').forEach(b=>b.onclick=()=>openEdit(Number(b.dataset.editPlaylist)));
 $('#playlist-list').querySelectorAll('[data-delete-playlist]').forEach(b=>b.onclick=()=>deletePlaylist(Number(b.dataset.deletePlaylist)));
}
function openEdit(id=null){
 const p=id===null?null:(account.playlists||[]).find(item=>Number(item.id)===id);
 if(id!==null&&!p)return;
 const form=$('#playlist');form.hidden=false;form.elements.playlist_id.value=p?String(p.id):'';
 form.elements.name.value=p?.name||'';form.elements.url.value=p?.url||'';form.elements.pin.value='';
 $('#edit-title').textContent=p?'تعديل القائمة':'إضافة قائمة شخصية';
 $('#pin-label').textContent=(account.playlists||[]).some(x=>x.pin_set)?'PIN الحالي للقوائم':'تعيين PIN للقوائم (4–8 أرقام)';
 $('#delete').hidden=!p;
}
async function busy(form,action){const buttons=[...form.querySelectorAll('button')];buttons.forEach(b=>b.disabled=true);try{await action();}catch(e){message(e.message);}finally{buttons.forEach(b=>b.disabled=false);}}
$('#login').onsubmit=e=>{e.preventDefault();busy(e.target,async()=>{
 credentials={mac:formatMac(e.target.elements.mac.value),key:e.target.elements.key.value.trim()};
 try{const data=await request('account');showAccount(data);e.target.reset();message('');}
 catch(error){credentials=null;throw error;}
});};
$('#edit').onclick=()=>openEdit();
$('#cancel').onclick=()=>{$('#playlist').hidden=true;message('');};
$('#playlist').onsubmit=e=>{e.preventDefault();busy(e.target,async()=>{
 const f=e.target.elements,id=f.playlist_id.value;
 const payload={name:f.name.value.trim(),url:f.url.value.trim(),pin:f.pin.value.trim()};
 await request(id?'playlists/'+encodeURIComponent(id):'playlists',id?'PUT':'POST',payload);
 showAccount(await request('account'));$('#playlist').hidden=true;message('تم حفظ القائمة بنجاح.');
});};
async function deletePlaylist(id){
 if(!confirm('حذف هذه القائمة نهائيًا؟'))return;
 const pin=prompt('أدخل PIN القوائم لتأكيد الحذف (4–8 أرقام):');
 if(pin===null)return;
 try{await request('playlists/'+encodeURIComponent(id),'DELETE',{pin:pin.trim()});showAccount(await request('account'));message('تم حذف القائمة.');}
 catch(error){message(error.message);}
}
$('#delete').onclick=()=>{const id=Number($('#playlist').elements.playlist_id.value);if(id)deletePlaylist(id);};
$('#logout').onclick=()=>{credentials=null;account=null;$('#playlist').reset();$('#account').hidden=true;$('#login').hidden=false;message('');};
