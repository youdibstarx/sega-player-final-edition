'use strict';
const form = document.querySelector('#login-form');
const status = document.querySelector('#login-status');
const submit = document.querySelector('#login-button');
form.addEventListener('submit', async event => {
  event.preventDefault();
  submit.disabled = true;
  status.textContent = 'جارٍ التحقق من الحساب...';
  try {
    const result = await fetch('/admin/api/login', {
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      headers: {'Content-Type':'application/json','X-Panel-Request':'1'},
      body: JSON.stringify({
        username: form.elements.username.value,
        password: form.elements.password.value
      })
    });
    if (!result.ok) {
      if (result.status === 401) throw Error('اسم المستخدم أو كلمة السر غير صحيحين.');
      if (result.status === 429) throw Error('محاولات كثيرة. جرّب من بعد شوية.');
      throw Error('تعذر تسجيل الدخول (' + result.status + ').');
    }
    form.elements.password.value = '';
    window.location.replace('/admin');
  } catch (error) {
    status.textContent = error.message;
    submit.disabled = false;
  }
});
