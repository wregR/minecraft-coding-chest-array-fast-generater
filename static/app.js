const $ = (selector) => document.querySelector(selector);
const state = { waterways: [], plan: null };

function radixChanged() {
  const binary = $('#radix').value === '2';
  $('#threads').innerHTML = binary ? '<option value="1">1 线程</option><option value="2">2 线程</option><option value="4">4 线程</option><option value="8">8 线程</option>' : '<option value="1">单线程</option>';
  $('#digitHint').textContent = binary ? '二进制：10、11 或 12 位。' : '十/十二进制：填写总数字位数，例如 3。';
  $('#groupWidthLabel').hidden = !binary;
  $('#digits').value = binary ? 10 : 3;
}

function config() {
  return { project_name: $('#projectName').value.trim() || '全物品编码箱', radix: Number($('#radix').value), threads: Number($('#threads').value), waterways: Number($('#waterways').value), total_digits: Number($('#digits').value), max_group_width: Number($('#groupWidth').value) };
}

function api(path, body) { return fetch(path, { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(body) }).then(async r => { const data = await r.json(); if (!r.ok) throw Error(data.error || '请求失败'); return data; }); }
function readFile(file) { return new Promise((resolve, reject) => { const reader = new FileReader(); reader.onerror = reject; reader.onload = () => resolve(reader.result); reader.readAsDataURL(file); }); }

function createGrid() {
  const cfg = config();
  if (cfg.radix === 2 && ![10,11,12].includes(cfg.total_digits)) return alert('二进制总码位只能选择 10、11 或 12。');
  state.waterways = [];
  const grid = $('#uploadGrid'); grid.innerHTML = '';
  const template = $('#cardTemplate');
  for (let t=0; t<cfg.threads; t++) for (let w=0; w<cfg.waterways; w++) {
    const node = template.content.firstElementChild.cloneNode(true);
    const threadCode = cfg.radix === 2 ? t.toString(2).padStart(Math.ceil(Math.log2(cfg.threads || 1)), '0') || '0' : '0';
    node.querySelector('h3').textContent = `线程 ${threadCode} · 水道 ${w}`;
    const record = { thread:t, waterway:w, name:`线程${threadCode}-水道${w}`, items:null };
    const input = node.querySelector('input'), status = node.querySelector('.state'), list = node.querySelector('ol');
    input.addEventListener('change', async () => {
      if (!input.files[0]) return;
      status.className = 'state'; status.textContent = '正在读取投影…';
      try {
        const parsed = await api('/api/parse-waterway', { file: await readFile(input.files[0]), name: input.files[0].name });
        record.items = parsed.items; record.name = input.files[0].name;
        status.className = 'state ok'; status.textContent = `已提取 ${parsed.items.length} 种物品，来自 ${parsed.containers} 个容器`;
        list.innerHTML = parsed.items.slice(0, 80).map(item => `<li>${item}</li>`).join('') + (parsed.items.length > 80 ? '<li>…</li>' : '');
      } catch (err) { record.items = null; status.className = 'state error'; status.textContent = err.message; }
      refreshButtons();
    });
    grid.append(node); state.waterways.push(record);
  }
  $('#uploadStatus').textContent = `需上传 ${state.waterways.length} 条水道`;
  $('#plan').className='empty'; $('#plan').textContent='上传全部水道投影后生成方案。'; state.plan=null; refreshButtons();
}

function refreshButtons() { const complete = state.waterways.length && state.waterways.every(row => Array.isArray(row.items)); $('#planButton').disabled=!complete; $('#exportButton').disabled=!state.plan; $('#uploadStatus').textContent = state.waterways.length ? `已上传 ${state.waterways.filter(x=>x.items).length}/${state.waterways.length}` : '尚未建立'; }

async function plan() {
  $('#plan').className='empty'; $('#plan').textContent='正在计算编码方案…';
  try {
    state.plan = await api('/api/plan', {config:config(), waterways:state.waterways});
    const p=state.plan, sizes=p.sizing;
    $('#plan').className=''; $('#plan').innerHTML=`<div class="plan-grid"><div class="metric"><span>物品总数</span><b>${p.items}</b></div><div class="metric"><span>编码双箱</span><b>${p.encoder_chests}</b></div><div class="metric"><span>自动位组</span><b>${p.partition.join('+')}</b></div><div class="metric"><span>每水道容量</span><b>${sizes.per_waterway}</b></div></div><div class="options">${p.options.map(x=>`<span class="option">${x.partition.join('+')}：${x.chests} 箱</span>`).join('')}</div>`;
    refreshButtons();
  } catch(err) { state.plan=null; $('#plan').className='empty'; $('#plan').textContent=err.message; refreshButtons(); }
}

async function exportProject() {
  try {
    $('#exportButton').disabled=true; $('#exportButton').textContent='正在生成…';
    const result=await api('/api/export',{config:config(),waterways:state.waterways});
    const link=document.createElement('a'); link.href=`data:application/zip;base64,${result.data}`; link.download=result.filename; link.click();
  } catch(err) { alert(err.message); } finally { $('#exportButton').textContent='下载编码投影包'; refreshButtons(); }
}

$('#radix').addEventListener('change',radixChanged); $('#createGrid').addEventListener('click',createGrid); $('#planButton').addEventListener('click',plan); $('#exportButton').addEventListener('click',exportProject); radixChanged(); createGrid();
