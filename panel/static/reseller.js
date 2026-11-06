'use strict';
const $=s=>document.querySelector(s);
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let authorization=null, pendingActivation=null;
const message=text=>{$('#message').textContent=text;};
function basic(email,password){
 const bytes=new TextEncoder().encode(email.trim().toLowerCase()+':'+password);
 return 'Basic '+btoa(Array.from(bytes,b=>String.fromCharCode(b)).join(''));
}
async function api(path,method='GET',payload){
 const response=await fetch('/reseller/api/'+path,{method,cache:'no-store',credentials:'omit',
 headers:{'Authorization':authorization,'X-Reseller-Request':'1','Content-Type':'application/json'},
 ...(payload!==undefined?{body:JSON.stringify(payload)}:{})});
 if(!response.ok){let error;try{error=(await response.json()).detail}catch{}
 throw Error(typeof error==='string'?error:'فشل الطلب ('+response.status+')');}
 return response.json();
}
const planLabels={six_months:'6 أشهر',year:'سنة',lifetime:'مدى الحياة'};
async function refresh(){
 const data=await api('account');
 $('#user').textContent=data.email;
 $('#role').textContent=data.role==='main'?'موزع رئيسي':'موزع فرعي';
 $('#balance').textContent=data.balance_points.toFixed(1);
 $('#sub-section').hidden=data.role!=='main';
 $('#children').innerHTML=data.children.map(c=>'<tr><td dir="ltr">'+esc(c.email)+'</td><td>'+c.points.toFixed(1)+'</td></tr>').join('')||'<tr><td colspan="2">لا يوجد موزعون فرعيون</td></tr>';
 $('#activations').innerHTML=data.activations.map(a=>'<tr><td dir="ltr">'+esc(a.mac_alias)+'</td><td>'+esc(planLabels[a.plan]||a.plan)+'</td><td>'+a.points.toFixed(1)+'</td><td>'+esc(a.expires_at||'مدى الحياة')+'</td><td>'+esc(a.created_at)+'</td></tr>').join('')||'<tr><td colspan="5">لا توجد تفعيلات</td></tr>';
 $('#transfers').innerHTML=data.transfers.map(t=>'<tr><td>'+esc(t.action==='admin_topup'?'شحن المدير':'تحويل')+'</td><td dir="ltr">'+esc(t.source_email||'المدير')+'</td><td dir="ltr">'+esc(t.target_email)+'</td><td>'+t.points.toFixed(1)+'</td><td>'+esc(t.created_at)+'</td></tr>').join('')||'<tr><td colspan="5">لا توجد تحويلات</td></tr>';
}
async function busy(form,task){
 const buttons=[...form.querySelectorAll('button')];buttons.forEach(b=>b.disabled=true);
 try{await task();}catch(err){message(err.message);}finally{buttons.forEach(b=>b.disabled=false);}
}
$('#login').onsubmit=e=>{e.preventDefault();busy(e.target,async()=>{
 const f=e.target.elements;authorization=basic(f.email.value,f.password.value);
 try{await refresh();$('#login').hidden=true;$('#account').hidden=false;e.target.reset();message('تم تسجيل الدخول');}
 catch(err){authorization=null;throw err;}
});};
$('#logout').onclick=()=>{authorization=null;pendingActivation=null;$('#account').hidden=true;$('#login').hidden=false;$('#mac-result').textContent='';message('');};
$('#check').onsubmit=e=>{e.preventDefault();busy(e.target,async()=>{
 const mac=e.target.elements.mac.value.trim().toUpperCase();
 const data=await api('mac/'+encodeURIComponent(mac));
 $('#mac-result').textContent=data.active?'✅ الجهاز مفعّل'+(data.expires_at?' حتى '+data.expires_at:' مدى الحياة'):'الجهاز مسجّل لكنه غير مفعّل / انتهت صلاحيته';
 $('#activate').elements.mac.value=mac;message('');
});};
$('#activate').onsubmit=e=>{e.preventDefault();busy(e.target,async()=>{
 const mac=e.target.elements.mac.value.trim().toUpperCase(),plan=e.target.elements.plan.value;
 if(!pendingActivation||pendingActivation.mac!==mac||pendingActivation.plan!==plan)
   pendingActivation={mac,plan,request_id:crypto.randomUUID()};
 if(!confirm('تأكيد تفعيل '+mac+' ('+planLabels[plan]+') وخصم النقاط من رصيدك؟'))return;
 const result=await api('activate','POST',pendingActivation);
 pendingActivation=null;await refresh();
 message(result.already_processed?'تم تأكيد التفعيل السابق دون خصم جديد':'تم التفعيل بنجاح — خصم '+result.points_charged+' نقطة');
});};
$('#create-sub').onsubmit=e=>{e.preventDefault();busy(e.target,async()=>{
 const data={email:e.target.elements.email.value.trim(),password:e.target.elements.password.value};
 await api('subresellers','POST',data);e.target.reset();await refresh();message('تم إنشاء الموزع الفرعي');
});};
$('#transfer').onsubmit=e=>{e.preventDefault();busy(e.target,async()=>{
 const data={email:e.target.elements.email.value.trim(),points:Number(e.target.elements.points.value)};
 if(!confirm('تأكيد تحويل '+data.points+' نقطة إلى '+data.email+'؟'))return;
 await api('transfer','POST',data);e.target.reset();await refresh();message('تم تحويل الرصيد');
});};
