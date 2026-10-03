// Tela de repositórios do CRUD. Tudo que vem da API entra na página por textContent, então nenhum valor vira HTML.
(function () {
  'use strict';

  var API = '/admin/v1/repositories';
  var rows = document.getElementById('rows');
  var msg = document.getElementById('msg');
  var form = document.getElementById('form');
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

  function cell(tr, text, className) {
    var td = document.createElement('td');
    if (className) { td.className = className; }
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

  function fill(repo) {
    field('fullName').value = repo.fullName;
    field('kind').value = repo.kind;
    field('enabled').checked = repo.enabled;
    field('notePt').value = repo.notePt || '';
    field('noteEn').value = repo.noteEn || '';
    form.scrollIntoView({ behavior: 'smooth' });
  }

  function render(list) {
    rows.textContent = '';
    if (!list.length) {
      var empty = document.createElement('tr');
      var td = cell(empty, 'Nenhum repositório ainda. Ligue o primeiro no formulário acima.', 'hint');
      td.colSpan = 7;
      rows.appendChild(empty);
      return;
    }
    list.forEach(function (repo) {
      var tr = document.createElement('tr');
      var name = cell(tr, repo.fullName);
      name.style.fontFamily = 'ui-monospace,Consolas,monospace';
      cell(tr, repo.kind);
      var state = document.createElement('td');
      state.appendChild(repo.enabled ? tag('ligado', 'on-tag') : tag('desligado', 'off-tag'));
      tr.appendChild(state);
      cell(tr, repo.defaultBranch);
      cell(tr, repo.sizeKb == null ? '' : repo.sizeKb + ' KB');
      cell(tr, repo.updatedBy);
      var actions = document.createElement('td');
      var edit = document.createElement('button');
      edit.type = 'button'; edit.className = 'ghost'; edit.textContent = 'Editar';
      edit.addEventListener('click', function () { fill(repo); });
      var remove = document.createElement('button');
      remove.type = 'button'; remove.className = 'danger'; remove.textContent = 'Remover';
      remove.style.marginLeft = '.4rem';
      remove.addEventListener('click', function () {
        if (!window.confirm('Remover ' + repo.fullName + ' da lista? Ele sai do snapshot na próxima publicação.')) { return; }
        api('DELETE', API + '/' + repo.fullName).then(function () {
          show('Removido: ' + repo.fullName + '. Publique para tirar do snapshot.', true);
          load();
        }).catch(function (e) { show(e.message, false); });
      });
      actions.appendChild(edit);
      actions.appendChild(remove);
      tr.appendChild(actions);
      rows.appendChild(tr);
    });
  }

  function load() {
    return api('GET', API).then(render).catch(function (e) { show(e.message, false); });
  }

  form.addEventListener('submit', function (event) {
    event.preventDefault();
    var button = field('save');
    button.disabled = true;
    api('PUT', API, {
      fullName: field('fullName').value.trim(),
      kind: field('kind').value.toUpperCase(),
      notePt: field('notePt').value.trim(),
      noteEn: field('noteEn').value.trim(),
      enabled: field('enabled').checked
    }).then(function (saved) {
      show('Salvo: ' + saved.fullName + (saved.enabled ? ' (ligado)' : ' (desligado)') + '. Agora clique em Publicar.', true);
      form.reset();
      field('enabled').checked = true;
      return load();
    }).catch(function (e) { show(e.message, false); })
      .then(function () { button.disabled = false; });
  });

  field('clear').addEventListener('click', function () { form.reset(); field('enabled').checked = true; });

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
              ? 'Publicado. O indexador da IA lê o snapshot novo em até 5 minutos.'
              : 'A publicação terminou com estado: ' + status + '. Veja o relatório na API (GET /admin/v1/jobs/' + started.jobId + ').',
              status === 'succeeded');
            return;
          }
          attempts += 1;
          window.setTimeout(poll, 1500);
        }).catch(function (e) { button.disabled = false; state.textContent = ''; show(e.message, false); });
      })();
    }).catch(function (e) { button.disabled = false; state.textContent = ''; show(e.message, false); });
  });

  load();
})();
