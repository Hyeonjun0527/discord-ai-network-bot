// NEXA 데스크톱 — orb.js (index.html 에서 분리, SoC/SRP). 동작 보존 verbatim.
    import * as THREE from 'three';

    const reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    // 테마 토큰을 CSS(:root)에서 읽어 WebGL 에 동일 적용 — 하드코딩 금지(단일 진실원천).
    const css = getComputedStyle(document.documentElement);
    const tk = (n, fb) => { const v = css.getPropertyValue(n).trim(); return (v && v[0] === '#') ? v : fb; };
    const COL = {
      cyan: tk('--c-cyan', '#5beaff'), blue: tk('--c-blue', '#5b8cff'),
      violet: tk('--c-violet', '#9b6bff'), purple: tk('--c-purple', '#8b5bff'), ok: tk('--ok', '#4dea98'),
    };
    const C = (h) => new THREE.Color(h);
    const rgbaHex = (hex, a) => { hex = hex.replace('#', ''); if (hex.length === 3) hex = hex.split('').map(c => c + c).join(''); const n = parseInt(hex, 16); return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`; };

    // 공유 RAF 루프: 등록된 씬 중 화면에 보이는 것만 렌더 → 가볍게 유지.
    const scenes = [];
    function register(canvas, fov, camZ, build) {
      let renderer;
      try { renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true }); }
      catch (e) { return; }
      renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
      renderer.outputColorSpace = THREE.SRGBColorSpace;
      const scene = new THREE.Scene();
      const camera = new THREE.PerspectiveCamera(fov, 1, 0.1, 100);
      camera.position.z = camZ;
      const api = { renderer, scene, camera, visible: true, mx: 0, my: 0 };
      const update = build(api);
      function resize() {
        const w = canvas.clientWidth || 1, h = canvas.clientHeight || 1;
        renderer.setSize(w, h, false); camera.aspect = w / h; camera.updateProjectionMatrix();
      }
      resize();
      new ResizeObserver(resize).observe(canvas);
      new IntersectionObserver((es) => { api.visible = es[0].isIntersecting; }, { threshold: 0.01 }).observe(canvas);
      scenes.push({ api, update });
    }

    // 마우스 패럴랙스(전역) — 각 씬이 참조
    let pmx = 0, pmy = 0;
    window.addEventListener('pointermove', (e) => {
      pmx = (e.clientX / window.innerWidth) * 2 - 1;
      pmy = (e.clientY / window.innerHeight) * 2 - 1;
    }, { passive: true });

    // ── 전역 배경은 별도 Canvas 2D 스크립트(아래)에서 처리. three.js 는 미니 위젯 전용. ──

    // ── 2) 상태 오브: 정이십면체 셸(원통) + 중앙 NEXA 로고 글래스 코인 ──
    const orbCanvas = document.querySelector('canvas[data-scene="orb"]');
    if (orbCanvas) register(orbCanvas, 42, 4.4, (api) => {
      const { scene } = api;
      // 조명(코인 본체 Physical 머티리얼용) — 로고 plane 은 unlit 이라 영향 없음
      scene.add(new THREE.AmbientLight(0x4a5680, 1.6));
      const lv = new THREE.PointLight(C(COL.violet), 14, 12); lv.position.set(2.4, 2.0, 3); scene.add(lv);
      const lc = new THREE.PointLight(C(COL.cyan), 10, 12); lc.position.set(-2.2, -1.6, 2.6); scene.add(lc);

      const group = new THREE.Group(); scene.add(group);

      // 와이어프레임 셸 2겹 + 정점 글로우(원통/구체 인상)
      const shell = new THREE.Mesh(new THREE.IcosahedronGeometry(1.34, 1), new THREE.MeshBasicMaterial({ color: C(COL.violet), wireframe: true, transparent: true, opacity: 0.7 }));
      group.add(shell);
      const shell2 = new THREE.Mesh(new THREE.IcosahedronGeometry(1.64, 1), new THREE.MeshBasicMaterial({ color: C(COL.blue), wireframe: true, transparent: true, opacity: 0.24 }));
      group.add(shell2);
      const dot = (() => { const c = document.createElement('canvas'); c.width = c.height = 64; const x = c.getContext('2d'); const g = x.createRadialGradient(32, 32, 0, 32, 32, 32); g.addColorStop(0, 'rgba(255,255,255,1)'); g.addColorStop(.4, 'rgba(150,200,255,.8)'); g.addColorStop(1, 'rgba(150,200,255,0)'); x.fillStyle = g; x.fillRect(0, 0, 64, 64); return new THREE.CanvasTexture(c); })();
      const verts = new THREE.Points(new THREE.IcosahedronGeometry(1.34, 1), new THREE.PointsMaterial({ size: 0.2, map: dot, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, color: C(COL.cyan) }));
      group.add(verts);

      // ── 중앙: NEXA 로고 글래스 코인 ──
      const coin = new THREE.Group(); group.add(coin);
      // 둥근 사각 본체(약간 두께 + 베벨) — 어두운 글래스
      const W = 1.32, rad = 0.26;
      const shape = new THREE.Shape();
      const x0 = -W / 2, y0 = -W / 2;
      shape.moveTo(x0 + rad, y0);
      shape.lineTo(x0 + W - rad, y0); shape.quadraticCurveTo(x0 + W, y0, x0 + W, y0 + rad);
      shape.lineTo(x0 + W, y0 + W - rad); shape.quadraticCurveTo(x0 + W, y0 + W, x0 + W - rad, y0 + W);
      shape.lineTo(x0 + rad, y0 + W); shape.quadraticCurveTo(x0, y0 + W, x0, y0 + W - rad);
      shape.lineTo(x0, y0 + rad); shape.quadraticCurveTo(x0, y0, x0 + rad, y0);
      const bodyGeo = new THREE.ExtrudeGeometry(shape, { depth: 0.2, bevelEnabled: true, bevelThickness: 0.05, bevelSize: 0.05, bevelSegments: 4, curveSegments: 16 });
      bodyGeo.center();
      const body = new THREE.Mesh(bodyGeo, new THREE.MeshPhysicalMaterial({ color: 0x0a1120, metalness: 0.4, roughness: 0.22, clearcoat: 1, clearcoatRoughness: 0.2, emissive: 0x141d38, emissiveIntensity: 0.5 }));
      coin.add(body);

      // 로고 텍스처(같은 오리진 → taint 없음). 앞/뒤 양면(back-to-back) → 회전해도 정상 로고.
      const loader = new THREE.TextureLoader();
      const logoTex = loader.load('img/nexa-logo.png');
      logoTex.colorSpace = THREE.SRGBColorSpace;
      logoTex.anisotropy = api.renderer.capabilities.getMaxAnisotropy();
      const faceGeo = new THREE.PlaneGeometry(W * 0.92, W * 0.92);
      const half = 0.2 / 2 + 0.05 + 0.012; // depth/2 + bevel + ε
      const front = new THREE.Mesh(faceGeo, new THREE.MeshBasicMaterial({ map: logoTex, transparent: true }));
      front.position.z = half;
      const back = new THREE.Mesh(faceGeo, new THREE.MeshBasicMaterial({ map: logoTex, transparent: true }));
      back.position.z = -half; back.rotation.y = Math.PI;
      coin.add(front); coin.add(back);

      // 코인 뒤 브랜드 후광(additive)
      const halo = (() => { const c = document.createElement('canvas'); c.width = c.height = 128; const x = c.getContext('2d'); const g = x.createRadialGradient(64, 64, 0, 64, 64, 64); g.addColorStop(0, rgbaHex(COL.violet, 0.55)); g.addColorStop(.5, rgbaHex(COL.blue, 0.18)); g.addColorStop(1, rgbaHex(COL.blue, 0)); x.fillStyle = g; x.fillRect(0, 0, 128, 128); return new THREE.CanvasTexture(c); })();
      const haloMesh = new THREE.Mesh(new THREE.PlaneGeometry(3.0, 3.0), new THREE.MeshBasicMaterial({ map: halo, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending }));
      haloMesh.position.z = -0.35; group.add(haloMesh);

      // 상태별 타깃(색·셸 색·후광 틴트·회전 속도 계수)
      const STATE_TINT = {
        ok:     { shell: C(COL.violet), shell2: C(COL.blue), halo: new THREE.Color(0xffffff), speed: 1.0 },
        paused: { shell: new THREE.Color(0x8a93ad), shell2: new THREE.Color(0x6f7c95), halo: new THREE.Color(0x9aa6bf), speed: 0.25 },
        error:  { shell: new THREE.Color(0xe3a53f), shell2: new THREE.Color(0xc77a3a), halo: new THREE.Color(0xffb86b), speed: 1.0 },
      };
      let mx = 0, my = 0, spd = 1;
      return (t) => {
        mx += (pmx - mx) * 0.05; my += (pmy - my) * 0.05;
        const tint = STATE_TINT[window.__orbState] || STATE_TINT.ok;
        shell.material.color.lerp(tint.shell, 0.06);
        shell2.material.color.lerp(tint.shell2, 0.06);
        haloMesh.material.color.lerp(tint.halo, 0.06);
        spd += (tint.speed - spd) * 0.05;
        // 셸은 천천히 독립 회전, 코인은 코인처럼 Y축 회전 + 미세 부유
        shell.rotation.y = t * 0.18 * spd + mx * 0.3; shell.rotation.x = Math.sin(t * 0.25) * 0.2 + my * 0.3;
        shell2.rotation.y = -t * 0.12 * spd; shell2.rotation.x = Math.cos(t * 0.2) * 0.18;
        verts.rotation.copy(shell.rotation);
        coin.rotation.y = t * 0.5 * spd + mx * 0.4;
        coin.rotation.x = my * 0.25 + Math.sin(t * 0.6) * 0.06;
        coin.position.y = Math.sin(t * 0.9) * 0.05;
        haloMesh.position.y = coin.position.y;
        // error 일 때 후광 빠르게 깜빡(경고), 그 외 부드럽게 맥동
        const isErr = window.__orbState === 'error';
        const pulse = isErr ? (0.5 + (Math.sin(t * 6) * 0.5 + 0.5) * 0.7) : (0.85 + (Math.sin(t * 1.6) * 0.5 + 0.5) * 0.3);
        haloMesh.material.opacity = pulse;
      };
    });

    // ── 3) Ollama 미니: 노드 클러스터(허브 + 위성, 네온 연결) ──
    const ollamaCanvas = document.querySelector('canvas[data-scene="ollama"]');
    if (ollamaCanvas) register(ollamaCanvas, 45, 5, (api) => {
      const { scene } = api;
      const group = new THREE.Group(); scene.add(group);
      const hub = new THREE.Mesh(new THREE.SphereGeometry(0.34, 20, 20), new THREE.MeshBasicMaterial({ color: C(COL.cyan) }));
      group.add(hub);
      const halo = new THREE.Mesh(new THREE.SphereGeometry(0.5, 20, 20), new THREE.MeshBasicMaterial({ color: C(COL.cyan), transparent: true, opacity: 0.18, blending: THREE.AdditiveBlending }));
      group.add(halo);
      const sats = [];
      const M = 6;
      const linePos = new Float32Array(M * 2 * 3);
      const lGeo = new THREE.BufferGeometry(); lGeo.setAttribute('position', new THREE.BufferAttribute(linePos, 3));
      const links = new THREE.LineSegments(lGeo, new THREE.LineBasicMaterial({ color: C(COL.blue), transparent: true, opacity: 0.55, blending: THREE.AdditiveBlending }));
      group.add(links);
      for (let i = 0; i < M; i++) {
        const m = new THREE.Mesh(new THREE.SphereGeometry(0.12, 14, 14), new THREE.MeshBasicMaterial({ color: C(COL.violet) }));
        const a = (i / M) * Math.PI * 2, r = 1.1, tilt = Math.sin(i * 2.1) * 0.5;
        m.userData = { a, r, tilt, sp: 0.5 + (i % 3) * 0.18 };
        group.add(m); sats.push(m);
      }
      return (t) => {
        group.rotation.y = t * 0.5; group.rotation.x = 0.4;
        sats.forEach((m, i) => {
          const u = m.userData; const a = u.a + t * u.sp;
          m.position.set(Math.cos(a) * u.r, u.tilt, Math.sin(a) * u.r);
          linePos[i * 6] = 0; linePos[i * 6 + 1] = 0; linePos[i * 6 + 2] = 0;
          linePos[i * 6 + 3] = m.position.x; linePos[i * 6 + 4] = m.position.y; linePos[i * 6 + 5] = m.position.z;
        });
        lGeo.attributes.position.needsUpdate = true;
        halo.scale.setScalar(1 + Math.sin(t * 2) * 0.08);
      };
    });

    // ── 4) ComfyUI 미니: 회전하는 네온 프리즘(옥타헤드론) ──
    const sdCanvas = document.querySelector('canvas[data-scene="sd"]');
    if (sdCanvas) register(sdCanvas, 45, 4.6, (api) => {
      const { scene } = api;
      const group = new THREE.Group(); scene.add(group);
      const geo = new THREE.OctahedronGeometry(1.15, 0);
      const solid = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ color: C(COL.purple), transparent: true, opacity: 0.22, blending: THREE.AdditiveBlending }));
      group.add(solid);
      const edges = new THREE.LineSegments(new THREE.EdgesGeometry(geo), new THREE.LineBasicMaterial({ color: C(COL.pink || COL.violet), transparent: true, opacity: 0.95 }));
      edges.material.color = C(COL.violet);
      group.add(edges);
      const inner = new THREE.Mesh(new THREE.OctahedronGeometry(0.5, 0), new THREE.MeshBasicMaterial({ color: C(COL.cyan), transparent: true, opacity: 0.5, blending: THREE.AdditiveBlending }));
      group.add(inner);
      return (t) => {
        group.rotation.y = t * 0.7; group.rotation.x = t * 0.35;
        inner.rotation.y = -t * 1.1; inner.rotation.z = t * 0.6;
        solid.material.opacity = 0.16 + (Math.sin(t * 1.8) * 0.5 + 0.5) * 0.14;
        inner.scale.setScalar(1 + Math.sin(t * 2.4) * 0.12);
      };
    });

    // 공유 루프
    let t0 = performance.now();
    function tick() {
      const now = performance.now();
      const t = (now - t0) / 1000;
      for (const s of scenes) {
        if (!s.api.visible) continue;
        s.update(t);
        s.api.renderer.render(s.api.scene, s.api.camera);
      }
      requestAnimationFrame(tick);
    }
    if (!reduce) tick();
    else { for (const s of scenes) { s.update(0); s.api.renderer.render(s.api.scene, s.api.camera); } }
