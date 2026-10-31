'use strict';
const $=s=>document.querySelector(s);
let credentials=null;
const message=text=>{$('#message').textContent=text;};
async function request(path,method='GET',data){
 const response=await fetch('/portal/api/'+path,{method,cache:'no-store',credentials:'omit',headers:{'Content-Type':'application/json','X-Portal-Request':'1','X-Device-Mac':credentials.mac,'X-Device-Key':credentials.key},...(data?{body:JSON.stringify(data)}:{})});
 if(!response.ok)throw new Error(response.status===403?'تأكد من MAC ومفتاح الجهاز.':response.status===429?'محاولات كثيرة. انتظر دقيقة ثم أعد المحاولة.':'تعذّر إتمام العملية. حاول مجددًا.');
 return response.json();
}
function showAccount(data){
 $('#login').hidden=true;$('#account').hidden=false;
 const plan=data.plan==='trial'?'تجربة مجانية':data.expires_at?'تفعيل مؤقت':'مدى الحياة';
 $('#subscription').textContent=data.active?`${plan} — ${data.expires_at?'صالح حتى '+data.expires_at:'بدون تاريخ انتهاء'}`:'التفعيل منتهٍ أو موقوف — تواصل مع المدير';
 $('#playlist').elements.name.value=data.playlist?.name||'';
 $('#playlist').elements.url.value=data.playlist?.url||'';
 $('#delete').hidden=!data.playlist;
}
async function busy(form,action){const buttons=[...form.querySelectorAll('button')];buttons.forEach(b=>b.disabled=true);try{await action();}catch(e){message(e.message);}finally{buttons.forEach(b=>b.disabled=false);}}
$('#login').onsubmit=e=>{e.preventDefault();busy(e.target,async()=>{credentials={mac:e.target.elements.mac.value.trim().toUpperCase(),key:e.target.elements.key.value.trim()};try{const data=await request('account');showAccount(data);e.target.reset();message('');}catch(error){credentials=null;throw error;}});};
$('#playlist').onsubmit=e=>{e.preventDefault();busy(e.target,async()=>{await request('playlist','PUT',{name:e.target.elements.name.value.trim(),url:e.target.elements.url.value.trim()});showAccount(await request('account'));message('تم حفظ قائمتك. حدّث القوائم داخل التطبيق.');});};
$('#delete').onclick=()=>{if(confirm('حذف قائمتك الخاصة؟'))busy($('#playlist'),async()=>{await request('playlist','DELETE');showAccount(await request('account'));message('تم حذف القائمة.');});};
$('#logout').onclick=()=>{credentials=null;$('#playlist').reset();$('#account').hidden=true;$('#login').hidden=false;message('');};
