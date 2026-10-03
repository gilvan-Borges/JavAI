// Tela de FAQ do CRUD. Tudo que vem da API entra na página por textContent, então nenhum valor vira HTML.
(function () {
  'use strict';

  var API = '/admin/v1/faqs';
  var rows = document.getElementById('rows');
  var msg = document.getElementById('msg');
  var form = document.getElementById('form');
  var editing = null; // id do item em edição, ou null para criar
  var field = function (id) { return document.getElementById(id); };

  function show(text, ok) {
    msg.textContent = text;
    msg.className = ok ? 'ok' : 'err';
  }

  function api(method, url, body) {
    var options = { method: method, headers: { 'Accept': 'application/json' }, credentials: 'same-origin' };
    if (body !== undefined) {
      options.headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(body);
    }
    return fetch(url, options).then(function (response) {
      if (response.status === 204) { return null; }
      return response.text().then(function (text) {
        var data = null;
        try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
        if (!response.ok) {
          var detail = data && (data.detail || data.title) ? (data.detail || data.title) : 'Erro ' + response.status;
          throw new Error(detail);
        }
        return data;
      });
    });
  }

  function cell(tr, text) {
    var td = document.createElement('td');
    td.textContent = text == null ? '' : String(text);
    tr.appendChild(td);
    return td;
  }

  function tag(text, className) {
    var span = document.createElement('span');
    span.className = 'tag ' + className;
    span.textContent = text;
    return span;
  }

  function reset() {
    editing = null;
    form.reset();
    field('publishItem').checked = true;
    field('visibility').value = 'public';
    field('sortOrder').value = '0';
    document.getElementById('formTitle').textContent = 'Nova pergunta e resposta';
  }

  function edit(item) {
    editing = item.id;
    var d = item.data;
    field('questionPt').value = d.questionPt || '';
    field('questionEn').value = d.questionEn || '';
    field('answerPt').value = d.answerPt || '';
    field('answerEn').value = d.answerEn || '';
    field('visibility').value = d.visibility || 'public';
    field('sortOrder').value = d.sortOrder == null ? 0 : d.sortOrder;
    field('publishItem').checked = item.status === 'published';
    document.getElementById('formTitle').textContent = 'Editando uma pergunta';
    form.scrollIntoView({ behavior: 'smooth' });
  }

  function render(list) {
    rows.textContent = '';
    if (!list.length) {
      var empty = document.createElement('tr');
      var td = cell(empty, 'Nenhuma pergunta ainda. Escreva a primeira acima.');
      td.className = 'hint'; td.colSpan = 5;
      rows.appendChild(empty);
      return;
    }
    list.forEach(function (item) {
      var tr = document.createElement('tr');
      cell(tr, item.data.questionPt || item.data.questionEn || '(sem pergunta)');
      var state = document.createElement('td');
      state.appendChild(item.status === 'published' ? tag('publicada', 'on-tag') : tag('rascunho', 'off-tag'));
      tr.appendChild(state);
      cell(tr, item.data.visibility === 'public' ? 'pública' : 'privada');
      cell(tr, item.updatedBy);
      var actions = document.createElement('td');
      var editButton = document.createElement('button');
      editButton.type = 'button'; editButton.className = 'ghost'; editButton.textContent = 'Editar';
      editButton.addEventListener('click', function () { edit(item); });
      var remove = document.createElement('button');
      remove.type = 'button'; remove.className = 'danger'; remove.textContent = 'Apagar';
      remove.style.marginLeft = '.4rem';
      remove.addEventListener('click', function () {
        if (!window.confirm('Apagar esta pergunta? Ela sai da IA na próxima publicação.')) { return; }
        api('DELETE', API + '/' + item.id).then(function () {
          show('Apagada. Publique para tirar da IA.', true);
          if (editing === item.id) { reset(); }
          load();
        }).catch(function (e) { show(e.message, false); });
      });
      actions.appendChild(editButton);
      actions.appendChild(remove);
      tr.appendChild(actions);
      rows.appendChild(tr);
    });
  }

  function load() {
    return api('GET', API).then(render).catch(function (e) { show(e.message, false); });
  }

  function value(id) {
    var v = field(id).value.trim();
    return v === '' ? null : v;
  }

  form.addEventListener('submit', function (event) {
    event.preventDefault();
    var button = field('save');
    button.disabled = true;
    var input = {
      questionPt: value('questionPt'),
      questionEn: value('questionEn'),
      answerPt: value('answerPt'),
      answerEn: value('answerEn'),
      sortOrder: parseInt(field('sortOrder').value, 10) || 0,
      visibility: field('visibility').value
    };
    var wantsPublished = field('publishItem').checked;
    var saved = editing ? api('PUT', API + '/' + editing, input) : api('POST', API, input);
    saved.then(function (item) {
      if (!wantsPublished && item.status === 'published') {
        return api('PATCH', API + '/' + item.id + '/status', { status: 'draft' }).then(function () { return 'rascunho'; });
      }
      if (wantsPublished && item.status !== 'published') {
        return api('PATCH', API + '/' + item.id + '/status', { status: 'published' }).then(function () { return 'publicada'; });
      }
      return item.status === 'published' ? 'publicada' : 'rascunho';
    }).then(function (state) {
      show('Salvo como ' + state + '. ' + (state === 'publicada' ? 'Agora clique em Publicar agora no fim da página.' : 'Ela só vai para a IA depois de publicada.'), true);
      reset();
      return load();
    }).catch(function (e) { show(e.message, false); })
      .then(function () { button.disabled = false; });
  });

  field('clear').addEventListener('click', reset);

  field('publish').addEventListener('click', function () {
    var button = field('publish');
    var state = field('pubstate');
    button.disabled = true;
    state.textContent = 'Publicando…';
    api('POST', '/admin/v1/publish').then(function (started) {
      var attempts = 0;
      (function poll() {
        api('GET', '/admin/v1/jobs/' + started.jobId).then(function (job) {
          var status = job && job.status;
          if (status === 'succeeded' || status === 'failed' || attempts > 40) {
            button.disabled = false;
            state.textContent = '';
            show(status === 'succeeded'
              ? 'Publicado. A IA lê o snapshot novo em até 5 minutos; depois teste de novo na tela Treinar.'
              : 'A publicação terminou com estado: ' + status + '.', status === 'succeeded');
            return;
          }
          attempts += 1;
          window.setTimeout(poll, 1500);
        }).catch(function (e) { button.disabled = false; state.textContent = ''; show(e.message, false); });
      })();
    }).catch(function (e) { button.disabled = false; state.textContent = ''; show(e.message, false); });
  });

  // Vindo da tela Treinar da IA: a pergunta de teste já chega preenchida, no idioma em que foi feita.
  var params = new URLSearchParams(window.location.search);
  var question = params.get('q');
  if (question) {
    var english = params.get('lang') === 'en';
    field(english ? 'questionEn' : 'questionPt').value = question.slice(0, 300);
    field(english ? 'answerEn' : 'answerPt').focus();
    show('Pergunta preenchida a partir do teste. Escreva a resposta certa nos dois idiomas, salve e publique.', true);
  }

  load();
})();
