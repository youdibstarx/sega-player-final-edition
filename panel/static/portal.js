'use strict';
const $=s=>document.querySelector(s);let credentials=null,playlists=[];
const message=text=>{$('#message').textContent=text;};
async function request(path,method='GET',data){
 const response=await fetch('/portal/api/'+path,{method,cache:'no-store',credentials:'omit',headers:{'Content-Type':'application/json','X-Portal-Request':'1','X-Device-Mac':credentials.mac,'X-Device-Key':credentials.key},...(data!==undefined?{body:JSON.stringify(data)}:{})});
 if(!response.ok)throw new Error(response.status===403?'بيانات الجهاز أو PIN غير صحيحة.':response.status===404?'القائمة غير موجودة.':response.status===429?'محاولات كثيرة. انتظر ثم أعد المحاولة.':'تعذّر إتمام العملية. تأكد من البيانات.');
 return response.json();
}
function clearEditor(){$('#playlist').reset();$('#playlist').elements.id.value='';$('#editor-title').textContent='إضافة قائمة جديدة';$('#delete').hidden=true;}
function editList(id){const item=playlists.find(x=>x.id===id);if(!item)return;clearEditor();const form=$('#playlist');form.elements.id.value=item.id;form.elements.name.value=item.name;form.elements.url.value=item.url;$('#editor-title').textContent='تعديل: '+item.name;$('#delete').hidden=false;form.scrollIntoView({behavior:'smooth',block:'start'});}
function showAccount(data){
 $('#login').hidden=true;$('#account').hidden=false;
 const plan=data.plan==='trial'?'تجربة مجانية':data.expires_at?'تفعيل مؤقت':'مدى الحياة';
 $('#subscription').textContent=data.active?`${plan} — ${data.expires_at?'صالح حتى '+data.expires_at:'بدون تاريخ انتهاء'}`:'التفعيل منتهٍ أو موقوف — تواصل مع المدير';
 playlists=data.playlists||[];const box=$('#playlist-list');box.replaceChildren();
 if(!playlists.length){const p=document.createElement('p');p.textContent='ما زدت حتى قائمة دابا.';box.append(p);}
 playlists.forEach(item=>{const row=document.createElement('div');row.className='playlist-row';const label=document.createElement('span');label.textContent=item.name+(item.pin_enabled?' 🔒':'');const btn=document.createElement('button');btn.type='button';btn.className='secondary';btn.textContent='تعديل';btn.onclick=()=>editList(item.id);row.append(label,btn);box.append(row);});
 clearEditor();
}
async function busy(form,action){const buttons=[...form.querySelectorAll('button')];buttons.forEach(b=>b.disabled=true);try{await action();}catch(e){message(e.message);}finally{buttons.forEach(b=>b.disabled=false);}}
$('#login').onsubmit=e=>{e.preventDefault();busy(e.target,async()=>{credentials={mac:e.target.elements.mac.value.trim().toUpperCase(),key:e.target.elements.key.value.trim()};try{showAccount(await request('account'));e.target.reset();message('');}catch(error){credentials=null;throw error;}});};
$('#playlist').onsubmit=e=>{e.preventDefault();busy(e.target,async()=>{const form=e.target,el=form.elements,id=el.id.value;const data={name:el.name.value.trim(),url:el.url.value.trim()};if(el.pin.value)data.pin=el.pin.value;if(id){if(el.current_pin.value)data.current_pin=el.current_pin.value;if(el.remove_pin.checked)data.remove_pin=true;await request('playlists/'+id,'PUT',data);}else{await request('playlists','POST',data);}showAccount(await request('account'));message('تم حفظ القائمة. حدّث القوائم داخل التطبيق.');});};
$('#delete').onclick=()=>{const id=$('#playlist').elements.id.value;if(!id||!confirm('حذف هذه القائمة نهائياً؟'))return;busy($('#playlist'),async()=>{const pin=$('#playlist').elements.current_pin.value;await request('playlists/'+id,'DELETE',pin?{pin}:undefined);showAccount(await request('account'));message('تم حذف القائمة.');});};
$('#new-list').onclick=clearEditor;
$('#logout').onclick=()=>{credentials=null;playlists=[];clearEditor();$('#account').hidden=true;$('#login').hidden=false;message('');};
