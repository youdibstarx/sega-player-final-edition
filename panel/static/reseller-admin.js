'use strict';
const $=s=>document.querySelector(s);
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const message=s=>{$('#message').textContent=s;};
async function api(path,method='GET',payload){
 const response=await fetch('/admin/api/resellers'+path,{method,cache:'no-store',headers:{'X-Panel-Request':'1','Content-Type':'application/json'},...(payload!==undefined?{body:JSON.stringify(payload)}:{})});
 if(!response.ok){let err;try{err=(await response.json()).detail}catch{}
 throw Error(typeof err==='string'?err:'تعذر إكمال العملية ('+response.status+')');}
 return response.json();
}
async function reload(){
 const r=await api('');
 $('#resellers').innerHTML=r.resellers.map(x=>'<tr><td dir="ltr">'+esc(x.email)+'</td><td>'+(x.role==='main'?'رئيسي':'فرعي')+'</td><td dir="ltr">'+esc(x.parent_email||'—')+'</td><td>'+x.points.toFixed(1)+'</td><td>'+esc(x.created_at)+'</td></tr>').join('')||'<tr><td colspan="5">لا توجد حسابات بعد</td></tr>';
 $('#transfers').innerHTML=r.transfers.map(x=>'<tr><td>'+(x.action==='admin_topup'?'شحن المدير':'تحويل')+'</td><td dir="ltr">'+esc(x.source_email||'المدير')+'</td><td dir="ltr">'+esc(x.target_email)+'</td><td>'+x.points.toFixed(1)+'</td><td>'+esc(x.created_at)+'</td></tr>').join('')||'<tr><td colspan="5">لا توجد تحويلات</td></tr>';
 const labels={six_months:'6 أشهر',year:'سنة',lifetime:'مدى الحياة'};
 $('#activations').innerHTML=r.activations.map(x=>'<tr><td dir="ltr">'+esc(x.reseller_email)+'</td><td dir="ltr">'+esc(x.mac_alias)+'</td><td>'+esc(labels[x.plan])+'</td><td>'+x.points.toFixed(1)+'</td><td>'+esc(x.expires_at||'مدى الحياة')+'</td><td>'+esc(x.created_at)+'</td></tr>').join('')||'<tr><td colspan="6">لا توجد تفعيلات</td></tr>';
}
async function busy(form,job){const buttons=[...form.querySelectorAll('button')];buttons.forEach(x=>x.disabled=true);try{await job()}catch(e){message(e.message)}finally{buttons.forEach(x=>x.disabled=false)}}
$('#create').onsubmit=e=>{e.preventDefault();busy(e.target,async()=>{
 const payload={email:e.target.elements.email.value.trim(),password:e.target.elements.password.value};
 await api('','POST',payload);e.target.reset();await reload();message('تم إنشاء الموزع الرئيسي');
});};
$('#credit').onsubmit=e=>{e.preventDefault();busy(e.target,async()=>{
 const email=e.target.elements.email.value.trim(),points=Number(e.target.elements.points.value);
 if(!confirm('تأكيد شحن '+points+' نقطة للموزع '+email+'؟'))return;
 await api('/'+encodeURIComponent(email)+'/credit','POST',{points});e.target.reset();await reload();message('تم شحن الرصيد');
});};
reload().catch(e=>message('تعذر تحميل الحسابات: '+e.message));
