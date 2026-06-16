/* STRIKE PROTOCOL — browser CS-style FPS engine (Three.js). Client-only module. */
import * as THREE from "three";
import { Peer as RTCPeer } from "peerjs";
import { GLTFLoader, type GLTF } from "three/examples/jsm/loaders/GLTFLoader.js";
import * as SkeletonUtils from "three/examples/jsm/utils/SkeletonUtils.js";

import type { DataConnection } from "peerjs";

export interface GameOptions {
  sensitivity: number; mode?: "bots" | "mp"; name?: string; room?: string;
  fov?: number; volume?: number; xhairColor?: string; xhairSize?: number;
  gameMode?: "elim" | "defuse"; botDiff?: "easy" | "normal" | "hard"; roundTarget?: number;
  map?: "bazaar" | "compound";
}

const clamp = (v: number, a: number, b: number) => Math.max(a, Math.min(b, v));
// framerate-independent exponential damping (lerp(a,b,1-exp(-lambda*dt)))
const damp = (a: number, b: number, lambda: number, dt: number) => a + (b - a) * (1 - Math.exp(-lambda * dt));
const rand = (a: number, b: number) => a + Math.random() * (b - a);

type WeaponId = "rifle" | "smg" | "sniper" | "pistol" | "knife";

interface WeaponDef {
  name: string; dmg: number; headMul: number; rpm: number; mag: number;
  reserve: number; reloadT: number; spread: number; moveSpread: number;
  recoil: number; auto: boolean; price: number; range: number; melee: boolean; ads: number;
}

const WEAPONS: Record<WeaponId, WeaponDef> = {
  rifle:  { name: "AK-103", dmg: 36, headMul: 4, rpm: 640, mag: 30, reserve: 90,  reloadT: 2.1, spread: 0.008, moveSpread: 0.028, recoil: 0.0034, auto: true,  price: 2700, range: 150, melee: false, ads: 55 },
  smg:    { name: "MP-9",   dmg: 28, headMul: 3, rpm: 900, mag: 30, reserve: 120, reloadT: 1.7, spread: 0.011, moveSpread: 0.014, recoil: 0.002,  auto: true,  price: 1500, range: 110, melee: false, ads: 58 },
  sniper: { name: "SR-90",  dmg: 110, headMul: 3, rpm: 38, mag: 5,  reserve: 20,  reloadT: 2.8, spread: 0.0012, moveSpread: 0.05, recoil: 0.013,  auto: false, price: 4000, range: 260, melee: false, ads: 16 },
  pistol: { name: "P-57",   dmg: 50, headMul: 4, rpm: 360, mag: 12, reserve: 48,  reloadT: 1.4, spread: 0.009, moveSpread: 0.02,  recoil: 0.0028, auto: false, price: 0,    range: 120, melee: false, ads: 62 },
  knife:  { name: "TAC KNIFE", dmg: 75, headMul: 1.5, rpm: 140, mag: 0, reserve: 0, reloadT: 0, spread: 0, moveSpread: 0, recoil: 0, auto: false, price: 0, range: 2.4, melee: true, ads: 78 },
};

const WEAPON_ICONS: Record<WeaponId, string> = {
  rifle: "/tex/w_rifle.png", smg: "/tex/w_smg.png", sniper: "/tex/w_sniper.png",
  pistol: "/tex/w_pistol.png", knife: "/tex/w_knife.png",
};
const ICON_BY_NAME: Record<string, string> = {};
(Object.keys(WEAPONS) as WeaponId[]).forEach((w) => { ICON_BY_NAME[WEAPONS[w].name] = WEAPON_ICONS[w]; });

const BOT_NAMES = ["Viper", "Wraith", "Talon", "Cobra", "Hex", "Jackal", "Mamba", "Raze", "Fenrir", "Ghoul"];
const BUY_TIME = 5, LIVE_TIME = 90, END_TIME = 4;
const DM_LIMIT = 25, DM_TIME = 600;
const FRAG_PRICE = 300, SMOKE_PRICE = 200;

export function initGame(container: HTMLElement, opts: GameOptions): () => void {
  const ac = new AbortController();
  const sig = { signal: ac.signal };
  const sens = clamp(opts.sensitivity, 0.2, 3);
  const mp = opts.mode === "mp";
  const myName = (opts.name || "PLAYER").trim().slice(0, 12).toUpperCase() || "PLAYER";
  const myId = Math.random().toString(36).slice(2, 10);
  const room = (opts.room || "LOBBY").replace(/[^a-zA-Z0-9]/g, "").toUpperCase().slice(0, 16) || "LOBBY";
  const VOL = clamp(opts.volume ?? 0.7, 0, 1);
  const defuse = !mp && opts.gameMode === "defuse";
  // online: everyone must share one world — lock to bazaar until map sync exists in the lobby protocol
  const MAP: "bazaar" | "compound" = !mp && opts.map === "compound" ? "compound" : "bazaar";
  const ROUND_TARGET = clamp(Math.round(opts.roundTarget ?? 8), 4, 12);
  const HALF_ROUNDS = ROUND_TARGET - 1; // side switch after this many rounds (defuse)
  const DIFF = ({
    easy:   { aimErr: 1.7, react: 0.6,  turn: 3.0, burstA: 2, burstB: 4, nadeChance: 0.1,  coverHp: 30 },
    normal: { aimErr: 1.0, react: 0.35, turn: 4.2, burstA: 2, burstB: 5, nadeChance: 0.25, coverHp: 45 },
    hard:   { aimErr: 0.5, react: 0.16, turn: 5.6, burstA: 3, burstB: 6, nadeChance: 0.4,  coverHp: 55 },
  } as const)[opts.botDiff || "normal"];
  const XCOLOR = opts.xhairColor || "#9fe870";
  const XSIZE = clamp(opts.xhairSize ?? 1, 0.5, 2);

  // ============ renderer / scene ============
  // HI-FI STYLIZED MODE (Valorant/Overwatch direction): native res, AA, clean grade
  const DPR = Math.min(window.devicePixelRatio || 1, 2);
  const renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: "high-performance" });
  renderer.setPixelRatio(DPR);
  renderer.setSize(container.clientWidth, container.clientHeight);
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.08;
  const canvas = renderer.domElement;
  canvas.style.position = "absolute";
  canvas.style.inset = "0";
  container.appendChild(canvas);

  const scene = new THREE.Scene();
  const texLoader = new THREE.TextureLoader();
  const gltfLoader = new GLTFLoader();
  scene.fog = new THREE.Fog(0xc9d4dd, 80, 260);
  // gradient sky dome
  {
    const skyGeo = new THREE.SphereGeometry(320, 24, 16);
    const skyMat = new THREE.ShaderMaterial({
      side: THREE.BackSide,
      depthWrite: false,
      uniforms: {},
      vertexShader: "varying vec3 vP; void main(){ vP = position; gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }",
      fragmentShader: [
        "varying vec3 vP;",
        "void main(){",
        "  float h = normalize(vP).y;",
        "  vec3 top = vec3(0.32,0.51,0.72);",
        "  vec3 mid = vec3(0.74,0.78,0.78);",
        "  vec3 hor = vec3(0.95,0.83,0.62);",
        "  vec3 c = h > 0.25 ? mix(mid, top, smoothstep(0.25,0.9,h)) : mix(hor, mid, smoothstep(-0.05,0.25,h));",
        "  gl_FragColor = vec4(c,1.0);",
        "}",
      ].join("\n"),
    });
    const sky: THREE.Mesh<THREE.SphereGeometry, THREE.Material> = new THREE.Mesh(skyGeo, skyMat as THREE.Material);
    scene.add(sky);
    texLoader.load("/tex/sky.jpg", (t) => {
      t.colorSpace = THREE.SRGBColorSpace;
      t.mapping = THREE.EquirectangularReflectionMapping;
      sky.material.dispose();
      sky.material = new THREE.MeshBasicMaterial({ map: t, side: THREE.BackSide, depthWrite: false, fog: false });
      scene.environment = t;
      scene.environmentIntensity = 0.4;
    });
  }
  // sun disc glow
  {
    const sunCv = document.createElement("canvas"); sunCv.width = sunCv.height = 128;
    const c = sunCv.getContext("2d")!;
    const g = c.createRadialGradient(64, 64, 4, 64, 64, 62);
    g.addColorStop(0, "rgba(255,250,230,1)"); g.addColorStop(0.25, "rgba(255,235,180,0.85)"); g.addColorStop(1, "rgba(255,220,150,0)");
    c.fillStyle = g; c.fillRect(0, 0, 128, 128);
    const st = new THREE.CanvasTexture(sunCv); st.colorSpace = THREE.SRGBColorSpace;
    const sunSprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: st, transparent: true, depthWrite: false, fog: false }));
    sunSprite.position.set(180, 250, 100); sunSprite.scale.setScalar(120);
    scene.add(sunSprite);
  }

  const BASE_FOV = clamp(opts.fov ?? 78, 60, 110);
  const camera = new THREE.PerspectiveCamera(BASE_FOV, container.clientWidth / container.clientHeight, 0.05, 400);
  camera.rotation.order = "YXZ";
  scene.add(camera);



  const hemi = new THREE.HemisphereLight(0xbfd9f2, 0xa08a62, 1.05);
  scene.add(hemi);
  const sun = new THREE.DirectionalLight(0xfff2da, 2.6);
  sun.position.set(45, 70, 25);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  sun.shadow.camera.left = -65; sun.shadow.camera.right = 65;
  sun.shadow.camera.top = 65; sun.shadow.camera.bottom = -65;
  sun.shadow.camera.far = 200;
  sun.shadow.bias = -0.0004;
  scene.add(sun);

  // ============ procedural textures ============
  function makeTex(painter: (c: CanvasRenderingContext2D) => void, rx: number, ry: number): THREE.CanvasTexture {
    const cv = document.createElement("canvas");
    cv.width = cv.height = 256;
    painter(cv.getContext("2d")!);
    const t = new THREE.CanvasTexture(cv);
    t.wrapS = t.wrapT = THREE.RepeatWrapping;
    t.repeat.set(rx, ry);
    t.colorSpace = THREE.SRGBColorSpace;
    t.anisotropy = 8;
    return t;
  }
  function speckle(c: CanvasRenderingContext2D, n: number, alpha: number) {
    for (let i = 0; i < n; i++) {
      const v = Math.floor(rand(-28, 28));
      c.fillStyle = `rgba(${v > 0 ? 255 : 0},${v > 0 ? 255 : 0},${v > 0 ? 230 : 0},${alpha})`;
      c.fillRect(rand(0, 256), rand(0, 256), rand(1, 3), rand(1, 3));
    }
  }
  const sandTexPaint = (c: CanvasRenderingContext2D) => {
    c.fillStyle = "#c4a877"; c.fillRect(0, 0, 256, 256);
    for (let y = 0; y < 256; y += 32) {
      c.fillStyle = `rgba(120,90,50,${rand(0.05, 0.14)})`;
      c.fillRect(0, y, 256, 3);
    }
    speckle(c, 1600, 0.07);
    c.strokeStyle = "rgba(90,70,40,0.25)";
    for (let i = 0; i < 8; i++) {
      c.beginPath();
      let x = rand(0, 256), y = rand(0, 256);
      c.moveTo(x, y);
      for (let k = 0; k < 4; k++) { x += rand(-22, 22); y += rand(8, 30); c.lineTo(x, y); }
      c.stroke();
    }
  };
  const groundPaint = (c: CanvasRenderingContext2D) => {
    c.fillStyle = "#b29568"; c.fillRect(0, 0, 256, 256);
    speckle(c, 2400, 0.08);
    for (let i = 0; i < 14; i++) {
      c.fillStyle = `rgba(140,115,75,${rand(0.1, 0.25)})`;
      c.beginPath();
      c.ellipse(rand(0, 256), rand(0, 256), rand(10, 40), rand(6, 20), rand(0, 3), 0, 7);
      c.fill();
    }
  };
  const cratePaint = (c: CanvasRenderingContext2D) => {
    c.fillStyle = "#8a6336"; c.fillRect(0, 0, 256, 256);
    for (let y = 0; y < 256; y += 42) {
      c.fillStyle = `rgba(60,40,18,0.5)`; c.fillRect(0, y, 256, 3);
      c.fillStyle = `rgba(255,220,160,0.07)`; c.fillRect(0, y + 3, 256, 4);
    }
    speckle(c, 900, 0.06);
    c.strokeStyle = "rgba(50,32,12,0.8)"; c.lineWidth = 10;
    c.strokeRect(5, 5, 246, 246);
    c.lineWidth = 7;
    c.beginPath(); c.moveTo(8, 8); c.lineTo(248, 248); c.moveTo(248, 8); c.lineTo(8, 248); c.stroke();
  };
  const plasterPaint = (c: CanvasRenderingContext2D) => {
    c.fillStyle = "#cfc0a0"; c.fillRect(0, 0, 256, 256);
    speckle(c, 2000, 0.05);
    for (let i = 0; i < 6; i++) {
      c.fillStyle = "rgba(160,140,105," + rand(0.15, 0.3).toFixed(2) + ")";
      c.fillRect(rand(0, 200), rand(0, 200), rand(20, 70), rand(20, 70));
    }
    c.strokeStyle = "rgba(110,95,65,0.5)"; c.lineWidth = 1.5;
    for (let i = 0; i < 5; i++) {
      c.beginPath();
      let x = rand(0, 256), y = 0;
      c.moveTo(x, y);
      while (y < 256) { x += rand(-14, 14); y += rand(20, 50); c.lineTo(x, y); }
      c.stroke();
    }
    c.fillStyle = "rgba(90,75,50,0.35)";
    c.fillRect(0, 236, 256, 20);
  };
  const metalPaint = (c: CanvasRenderingContext2D) => {
    c.fillStyle = "#5d6a66"; c.fillRect(0, 0, 256, 256);
    for (let y = 0; y < 256; y += 22) { c.fillStyle = "rgba(30,38,36,0.5)"; c.fillRect(0, y, 256, 2); }
    speckle(c, 700, 0.08);
    for (let i = 0; i < 18; i++) {
      c.fillStyle = "rgba(140,80,40," + rand(0.1, 0.45).toFixed(2) + ")";
      c.beginPath(); c.ellipse(rand(0, 256), rand(0, 256), rand(4, 22), rand(3, 12), rand(0, 3), 0, 7); c.fill();
    }
  };
  function genNormalFrom(t: THREE.Texture, rx: number, ry: number, strength: number): THREE.CanvasTexture {
    const img = t.image as HTMLImageElement;
    const SZ = 256;
    const cv = document.createElement("canvas"); cv.width = cv.height = SZ;
    const c = cv.getContext("2d")!;
    c.drawImage(img, 0, 0, SZ, SZ);
    const src = c.getImageData(0, 0, SZ, SZ).data;
    const lum = (x: number, y: number) => {
      const i = (((y + SZ) % SZ) * SZ + ((x + SZ) % SZ)) * 4;
      return src[i] * 0.299 + src[i + 1] * 0.587 + src[i + 2] * 0.114;
    };
    const out = c.createImageData(SZ, SZ);
    for (let y = 0; y < SZ; y++) {
      for (let x = 0; x < SZ; x++) {
        const dx = (lum(x + 1, y) - lum(x - 1, y)) * strength;
        const dy = (lum(x, y + 1) - lum(x, y - 1)) * strength;
        const inv = 1 / Math.hypot(dx, dy, 255);
        const i = (y * SZ + x) * 4;
        out.data[i] = (-dx * inv * 0.5 + 0.5) * 255;
        out.data[i + 1] = (-dy * inv * 0.5 + 0.5) * 255;
        out.data[i + 2] = (255 * inv * 0.5 + 0.5) * 255;
        out.data[i + 3] = 255;
      }
    }
    c.putImageData(out, 0, 0);
    const nt = new THREE.CanvasTexture(cv);
    nt.wrapS = nt.wrapT = THREE.RepeatWrapping;
    nt.repeat.set(rx, ry);
    return nt;
  }
  function loadTex(url: string, rx: number, ry: number, onto: THREE.MeshStandardMaterial[], normalStrength = 0) {
    texLoader.load(url, (t) => {
      t.wrapS = t.wrapT = THREE.RepeatWrapping;
      t.repeat.set(rx, ry);
      t.colorSpace = THREE.SRGBColorSpace;
      t.anisotropy = 8;
      t.magFilter = THREE.LinearFilter;
      t.minFilter = THREE.LinearMipmapLinearFilter;
      t.generateMipmaps = true;
      let nt: THREE.CanvasTexture | null = null;
      if (normalStrength > 0) {
        try { nt = genNormalFrom(t, rx, ry, normalStrength); } catch { nt = null; }
      }
      onto.forEach((m) => {
        m.map = t;
        if (nt) { m.normalMap = nt; m.normalScale.set(0.4, 0.4); }
        m.needsUpdate = true;
      });
    });
  }
  const wallMat = new THREE.MeshStandardMaterial({ map: makeTex(sandTexPaint, 2, 1), roughness: 0.96 });
  const plasterMat = new THREE.MeshStandardMaterial({ map: makeTex(plasterPaint, 2, 1), roughness: 0.95 });
  const metalMat = new THREE.MeshStandardMaterial({ map: makeTex(metalPaint, 1, 1), roughness: 0.6, metalness: 0.45 });
  const barrelMat = new THREE.MeshStandardMaterial({ map: makeTex(metalPaint, 2, 1), color: 0x9a7a60, roughness: 0.55, metalness: 0.35 });
  const awningMat = new THREE.MeshStandardMaterial({ color: 0x8a3b2e, roughness: 0.9, side: THREE.DoubleSide });
  const awningMat2 = new THREE.MeshStandardMaterial({ color: 0x3e5e4f, roughness: 0.9, side: THREE.DoubleSide });
  const trunkMat = new THREE.MeshStandardMaterial({ color: 0x6e573a, roughness: 1 });
  const frondMat = new THREE.MeshStandardMaterial({ color: 0x4f7a3a, roughness: 0.9, side: THREE.DoubleSide });
  const sandbagMat = new THREE.MeshStandardMaterial({ map: makeTex(sandTexPaint, 1, 1), color: 0xb5a37c, roughness: 1 });
  const wallMat2 = new THREE.MeshStandardMaterial({ map: makeTex(sandTexPaint, 4, 1), color: 0xddd0b5, roughness: 0.96 });
  const crateMat = new THREE.MeshStandardMaterial({ map: makeTex(cratePaint, 1, 1), roughness: 0.9 });
  const groundMat = new THREE.MeshStandardMaterial({ map: makeTex(groundPaint, 18, 18), roughness: 1 });
  loadTex("/tex/wall.jpg", 1.6, 0.9, [wallMat], 1.4);
  loadTex("/tex/wall.jpg", 3, 1.6, [wallMat2, plasterMat], 1.4);
  loadTex("/tex/ground.jpg", 26, 26, [groundMat], 1.8);
  loadTex("/tex/crate.jpg", 1, 1, [crateMat], 1.6);
  loadTex("/tex/metal.jpg", 1, 1, [metalMat], 1.2);
  loadTex("/tex/metal.jpg", 1.6, 1, [barrelMat], 1.2);

  // ============ map ============
  const staticGroup = new THREE.Group();
  scene.add(staticGroup);
  const walls: THREE.Box3[] = [];
  const mapRects: { x: number; z: number; w: number; d: number }[] = [];

  function solid(x: number, z: number, w: number, d: number, h: number, mat: THREE.Material, yBase = 0) {
    const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), mat);
    m.position.set(x, yBase + h / 2, z);
    m.castShadow = true; m.receiveShadow = true;
    staticGroup.add(m);
    walls.push(new THREE.Box3(new THREE.Vector3(x - w / 2, yBase, z - d / 2), new THREE.Vector3(x + w / 2, yBase + h, z + d / 2)));
    if (yBase < 1.5) mapRects.push({ x, z, w, d });
  }

  const ground = new THREE.Mesh(new THREE.PlaneGeometry(360, 360), groundMat);
  ground.rotation.x = -Math.PI / 2;
  ground.receiveShadow = true;
  scene.add(ground);
  // scattered rubble stones
  {
    const stoneGeo = new THREE.DodecahedronGeometry(0.16, 0);
    const stoneMat = new THREE.MeshStandardMaterial({ color: 0x9a8a6a, roughness: 1 });
    const stones = new THREE.InstancedMesh(stoneGeo, stoneMat, 160);
    const m4 = new THREE.Matrix4();
    for (let i = 0; i < 160; i++) {
      const sx = rand(-48, 48), sz = rand(-48, 48), sc = rand(0.3, 1.4);
      m4.makeRotationY(rand(0, 3));
      m4.scale(new THREE.Vector3(sc, sc * 0.6, sc));
      m4.setPosition(sx, 0.05 * sc, sz);
      stones.setMatrixAt(i, m4);
    }
    stones.receiveShadow = true;
    scene.add(stones);
  }

  // perimeter (both maps)
  solid(0, -52, 106, 2, 7, wallMat2); solid(0, 52, 106, 2, 7, wallMat2);
  solid(-52, 0, 2, 106, 7, wallMat2); solid(52, 0, 2, 106, 7, wallMat2);
  if (MAP === "bazaar") {
  // west long wall x=-20 (gaps z 5..11 and -22..-16)
  solid(-20, -26, 1.2, 8, 4.5, wallMat); solid(-20, -5.5, 1.2, 21, 4.5, wallMat); solid(-20, 20.5, 1.2, 19, 4.5, wallMat);
  // east long wall x=20
  solid(20, -26, 1.2, 8, 4.5, wallMat); solid(20, -5.5, 1.2, 21, 4.5, wallMat); solid(20, 20.5, 1.2, 19, 4.5, wallMat);
  // mid doors wall z=-12 (gap |x|<3)
  solid(-11.5, -12, 17, 1.2, 4.5, wallMat); solid(11.5, -12, 17, 1.2, 4.5, wallMat);
  solid(0, -12, 7.5, 1.2, 1.2, wallMat, 3.3); // door lintel
  // mid dividers
  solid(-8, 8, 1.2, 24, 4.5, wallMat); solid(8, 8, 1.2, 24, 4.5, wallMat);
  // gate lintels
  solid(-20, 8, 1.2, 6.4, 1.2, wallMat, 3.3); solid(20, 8, 1.2, 6.4, 1.2, wallMat, 3.3);
  // site pillars
  solid(-28, -24, 1.4, 1.4, 4.5, wallMat); solid(28, -24, 1.4, 1.4, 4.5, wallMat);
  // crates: B site
  solid(-35, -31, 1.5, 1.5, 1.5, crateMat); solid(-33.3, -31.2, 1.5, 1.5, 1.5, crateMat);
  solid(-34.2, -31.1, 1.5, 1.5, 1.5, crateMat, 1.5); solid(-31.6, -28.6, 1.6, 1.6, 0.75, crateMat);
  // crates: A site
  solid(35, -31, 1.5, 1.5, 1.5, crateMat); solid(33.3, -31.2, 1.5, 1.5, 1.5, crateMat);
  solid(34.2, -31.1, 1.5, 1.5, 1.5, crateMat, 1.5); solid(31.6, -28.6, 1.6, 1.6, 0.75, crateMat);
  // mid cover
  solid(-2, 12, 1.5, 1.5, 1.5, crateMat); solid(-0.4, 12.2, 1.5, 1.5, 1.5, crateMat);
  solid(-1.2, 12.1, 1.5, 1.5, 1.5, crateMat, 1.5);
  solid(0.6, -26, 1.6, 1.6, 0.75, crateMat); solid(2.3, -25.6, 1.5, 1.5, 1.5, crateMat);
  // spawn + alley + lane crates
  solid(-14, 34, 1.6, 1.6, 0.75, crateMat); solid(14, 34, 1.6, 1.6, 0.75, crateMat);
  solid(-17, 6, 1.5, 1.5, 0.75, crateMat); solid(17, 6, 1.5, 1.5, 0.75, crateMat);
  solid(-24, -40, 1.5, 1.5, 1.5, crateMat); solid(24, -40, 1.5, 1.5, 1.5, crateMat);
  solid(-36, -2, 1.5, 1.5, 1.5, crateMat); solid(36, -2, 1.5, 1.5, 1.5, crateMat);
  } else {
  // ===== COMPOUND: tight CQB — central building, side corridors, north sites =====
  // central building shell (doors N/S/E/W)
  solid(-12, -10, 1.2, 8, 4.5, wallMat); solid(-12, 6, 1.2, 8, 4.5, wallMat);   // west wall, gap z -6..2
  solid(12, -10, 1.2, 8, 4.5, wallMat);  solid(12, 6, 1.2, 8, 4.5, wallMat);    // east wall
  solid(-7, -14, 10, 1.2, 4.5, wallMat); solid(7, -14, 10, 1.2, 4.5, wallMat);  // north wall, gap x -2..2
  solid(-7, 10, 10, 1.2, 4.5, wallMat);  solid(7, 10, 10, 1.2, 4.5, wallMat);   // south wall
  solid(0, -14, 5, 1.2, 1.2, wallMat, 3.3); solid(0, 10, 5, 1.2, 1.2, wallMat, 3.3); // lintels
  // side corridor walls x=±34 (gap z -16..-4)
  solid(-34, -22, 1.2, 12, 4.5, wallMat); solid(-34, 2, 1.2, 12, 4.5, wallMat);
  solid(34, -22, 1.2, 12, 4.5, wallMat);  solid(34, 2, 1.2, 12, 4.5, wallMat);
  // south funnel z=26 (center gap x -4..4, outer passes beyond ±32)
  solid(-18, 26, 28, 1.2, 4.5, wallMat); solid(18, 26, 28, 1.2, 4.5, wallMat);
  // north site wall z=-32 (center gap x -6..6, outer beyond ±30)
  solid(-18, -32, 24, 1.2, 4.5, wallMat); solid(18, -32, 24, 1.2, 4.5, wallMat);
  // courtyard cover
  solid(-1, -2, 1.5, 1.5, 1.5, crateMat); solid(0.6, -2.2, 1.5, 1.5, 1.5, crateMat);
  solid(-0.2, -2.1, 1.5, 1.5, 1.5, crateMat, 1.5);
  // corridor cover
  solid(-23, -16, 1.6, 1.6, 0.75, crateMat); solid(23, -16, 1.6, 1.6, 0.75, crateMat);
  solid(-41, 4, 1.5, 1.5, 1.5, crateMat); solid(41, 4, 1.5, 1.5, 1.5, crateMat);
  // site crates
  solid(-27, -44, 1.5, 1.5, 1.5, crateMat); solid(-25.3, -44.2, 1.5, 1.5, 1.5, crateMat, 0); solid(-26.2, -44.1, 1.5, 1.5, 1.5, crateMat, 1.5);
  solid(27, -44, 1.5, 1.5, 1.5, crateMat);  solid(25.3, -44.2, 1.5, 1.5, 1.5, crateMat, 0);  solid(26.2, -44.1, 1.5, 1.5, 1.5, crateMat, 1.5);
  // spawn cover
  solid(-12, 36, 1.6, 1.6, 0.75, crateMat); solid(12, 36, 1.6, 1.6, 0.75, crateMat);
  solid(-8, -46, 1.6, 1.6, 0.75, crateMat); solid(8, -46, 1.6, 1.6, 0.75, crateMat);
  }

  // ===== decorative environment (visual-only group has colliders where it matters) =====
  const barrelGeo = new THREE.CylinderGeometry(0.42, 0.42, 1.1, 12);
  function barrel(x: number, z: number, tint?: number) {
    const m = new THREE.Mesh(barrelGeo, tint ? new THREE.MeshStandardMaterial({ map: barrelMat.map, color: tint, roughness: 0.55, metalness: 0.35 }) : barrelMat);
    m.position.set(x, 0.55, z); m.rotation.y = rand(0, 3);
    m.castShadow = true; m.receiveShadow = true;
    staticGroup.add(m);
    walls.push(new THREE.Box3(new THREE.Vector3(x - 0.42, 0, z - 0.42), new THREE.Vector3(x + 0.42, 1.1, z + 0.42)));
  }
  barrel(-22.5, -13.5); barrel(22.5, -13.5, 0x4f6e52); barrel(-5.2, -13.8, 0x52525a);
  barrel(-30, 9.5, 0x4f6e52); barrel(30, 9.5); barrel(-15.5, 35, 0x52525a); barrel(16.5, 36);
  barrel(2.5, -41); barrel(-37.5, -31.5, 0x4f6e52);

  // sandbag nests
  function sandbags(cx: number, cz: number, ry: number) {
    const g = new THREE.Group();
    const bagGeo = new THREE.CapsuleGeometry(0.16, 0.36, 3, 6);
    for (let r = 0; r < 3; r++) {
      const n = 5 - r;
      for (let i = 0; i < n; i++) {
        const bag = new THREE.Mesh(bagGeo, sandbagMat);
        bag.rotation.z = Math.PI / 2;
        bag.rotation.y = rand(-0.12, 0.12);
        bag.position.set((i - (n - 1) / 2) * 0.62, 0.16 + r * 0.27, rand(-0.03, 0.03));
        bag.castShadow = true; bag.receiveShadow = true;
        g.add(bag);
      }
    }
    g.position.set(cx, 0, cz); g.rotation.y = ry;
    staticGroup.add(g);
    const hw = 1.65, hd = 0.45;
    const cos = Math.abs(Math.cos(ry)), sin = Math.abs(Math.sin(ry));
    const ex = hw * cos + hd * sin, ez = hw * sin + hd * cos;
    walls.push(new THREE.Box3(new THREE.Vector3(cx - ex, 0, cz - ez), new THREE.Vector3(cx + ex, 0.85, cz + ez)));
  }
  if (MAP === "bazaar") {
    sandbags(0, 30.5, 0); sandbags(-12, -19.5, 0.5); sandbags(12, -19.5, -0.5);
    sandbags(-27, 0, Math.PI / 2); sandbags(27, 0, Math.PI / 2);
  } else {
    sandbags(0, 14, 0); sandbags(-24, -18, Math.PI / 2); sandbags(24, -18, Math.PI / 2);
    sandbags(0, -28, 0);
  }

  // palm trees (visual only, in corners / dead zones)
  function palm(x: number, z: number, h: number) {
    const g = new THREE.Group();
    const segs = 5;
    for (let i = 0; i < segs; i++) {
      const t = new THREE.Mesh(new THREE.CylinderGeometry(0.16 - i * 0.018, 0.19 - i * 0.018, h / segs + 0.08, 7), trunkMat);
      t.position.set(Math.sin(i * 0.4) * 0.18, (i + 0.5) * (h / segs), 0);
      t.rotation.z = 0.06 * i;
      t.castShadow = true;
      g.add(t);
    }
    const top = new THREE.Vector3(Math.sin(segs * 0.32) * 0.3, h + 0.1, 0);
    for (let i = 0; i < 7; i++) {
      const frond = new THREE.Mesh(new THREE.PlaneGeometry(2.4, 0.5, 4, 1), frondMat);
      const pos = frond.geometry.attributes.position;
      for (let v = 0; v < pos.count; v++) {
        const fx = pos.getX(v);
        pos.setY(v, pos.getY(v) * (1 - Math.abs(fx) / 1.4) - fx * fx * 0.22);
      }
      pos.needsUpdate = true;
      frond.position.copy(top);
      frond.rotation.y = (i / 7) * Math.PI * 2;
      frond.rotation.z = -0.35;
      frond.castShadow = true;
      g.add(frond);
    }
    g.position.set(x, 0, z);
    g.rotation.y = rand(0, 3);
    staticGroup.add(g);
    walls.push(new THREE.Box3(new THREE.Vector3(x - 0.3, 0, z - 0.3), new THREE.Vector3(x + 0.3, h, z + 0.3)));
  }
  palm(-44, 44, 6); palm(44, 44, 5.2); palm(-44, -44, 5.6); palm(44, -44, 6.2);
  palm(-44, 0, 5); palm(44, 0, 5.8); palm(-8, 46, 4.8); palm(8, -48, 5.4);

  // market awnings on the long walls
  function awning(x: number, z: number, w: number, ry: number, mat: THREE.Material) {
    const a = new THREE.Mesh(new THREE.PlaneGeometry(w, 1.6, 6, 1), mat);
    const pos = a.geometry.attributes.position;
    for (let v = 0; v < pos.count; v++) {
      const fx = pos.getX(v);
      pos.setZ(v, Math.sin((fx / w) * Math.PI * 6) * 0.06);
    }
    pos.needsUpdate = true;
    a.position.set(x, 3.4, z);
    a.rotation.set(-0.55, ry, 0);
    a.castShadow = true;
    staticGroup.add(a);
    // poles
    for (const sx of [-w / 2 + 0.1, w / 2 - 0.1]) {
      const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.04, 0.04, 2.8, 6), trunkMat);
      const px = x + Math.cos(ry) * sx, pz = z - Math.sin(ry) * sx;
      pole.position.set(px + Math.sin(ry) * 0.7, 1.4, pz + Math.cos(ry) * 0.7);
      staticGroup.add(pole);
    }
  }
  if (MAP === "bazaar") {
    awning(-19.2, -2, 4, Math.PI / 2, awningMat); awning(19.2, -2, 4, -Math.PI / 2, awningMat2);
    awning(-19.2, 18, 4, Math.PI / 2, awningMat2); awning(19.2, 18, 4, -Math.PI / 2, awningMat);
    awning(-6, -11.2, 4.5, 0, awningMat); awning(6, -11.2, 4.5, 0, awningMat2);
  } else {
    awning(-11.2, -2, 4, Math.PI / 2, awningMat); awning(11.2, -2, 4, -Math.PI / 2, awningMat2);
    awning(0, 25.2, 5, 0, awningMat);
  }

  // rooftop parapets + distant skyline blocks for depth
  for (const [bx, bz, bw, bd, bh] of [
    [-60, -20, 14, 10, 9], [60, -25, 12, 14, 12], [-64, 25, 16, 12, 7], [62, 30, 10, 10, 10],
    [-25, -65, 18, 10, 8], [20, -68, 14, 12, 14], [0, 66, 22, 10, 9], [-55, 60, 12, 12, 11], [58, -60, 16, 10, 8],
  ] as const) {
    const b = new THREE.Mesh(new THREE.BoxGeometry(bw, bh, bd), plasterMat);
    b.position.set(bx, bh / 2 - 0.1, bz);
    scene.add(b);
    // windows
    const winMat = new THREE.MeshStandardMaterial({ color: 0x2a3038, roughness: 0.4 });
    for (let wy = 2; wy < bh - 1; wy += 2.4) {
      for (let wx = -bw / 2 + 1.4; wx < bw / 2 - 0.8; wx += 2.2) {
        const win = new THREE.Mesh(new THREE.BoxGeometry(0.9, 1.2, 0.1), winMat);
        win.position.set(bx + wx, wy, bz + bd / 2 + 0.03);
        scene.add(win);
      }
    }
  }

  // (power lines removed — they alias into broken black scanlines at low res)

  // ladder props against mid walls
  function ladder(x: number, z: number, ry: number) {
    const g = new THREE.Group();
    const railGeo = new THREE.BoxGeometry(0.06, 3.4, 0.06);
    for (const sx of [-0.25, 0.25]) {
      const r = new THREE.Mesh(railGeo, trunkMat); r.position.set(sx, 1.7, 0); g.add(r);
    }
    for (let y = 0.3; y < 3.3; y += 0.4) {
      const rung = new THREE.Mesh(new THREE.BoxGeometry(0.5, 0.05, 0.05), trunkMat);
      rung.position.set(0, y, 0); g.add(rung);
    }
    g.position.set(x, 0, z); g.rotation.y = ry;
    staticGroup.add(g);
  }
  if (MAP === "bazaar") { ladder(-9.2, 3, Math.PI / 2); ladder(9.2, 13, -Math.PI / 2); }
  else { ladder(-11.2, 4, Math.PI / 2); ladder(11.2, -8, -Math.PI / 2); }

  // hanging cloth banners (generated art)
  {
    const bannerMat = new THREE.MeshStandardMaterial({ roughness: 1, side: THREE.DoubleSide });
    texLoader.load("/tex/banner.jpg", (t) => {
      t.colorSpace = THREE.SRGBColorSpace;
      bannerMat.map = t; bannerMat.needsUpdate = true;
    });
    const bannerGeo = new THREE.PlaneGeometry(1.5, 2.2, 8, 8);
    const bannerSpots: readonly (readonly [number, number, number])[] = MAP === "bazaar" ? [
      [-19.3, -24, Math.PI / 2], [19.3, -24, -Math.PI / 2],
      [-7.3, 14, Math.PI / 2], [7.3, 14, -Math.PI / 2],
      [-11, -11.3, 0], [11, -11.3, 0],
    ] : [
      [-11.3, -6, Math.PI / 2], [11.3, -6, -Math.PI / 2],
      [-5, 9.3, 0], [5, -13.3, 0],
    ];
    for (const [bx, bz, ry] of bannerSpots) {
      const b = new THREE.Mesh(bannerGeo.clone(), bannerMat);
      const pos = b.geometry.attributes.position;
      for (let v = 0; v < pos.count; v++) {
        const vy = pos.getY(v), vx = pos.getX(v);
        pos.setZ(v, Math.sin(vx * 4 + vy * 2) * 0.05 * (1 - (vy + 1.1) / 2.2));
      }
      pos.needsUpdate = true;
      b.geometry.computeVertexNormals();
      b.position.set(bx, 2.6, bz);
      b.rotation.y = ry;
      b.castShadow = true;
      staticGroup.add(b);
    }
  }

  // perf: static world never moves — freeze matrices (three.js best practice)
  staticGroup.updateMatrixWorld(true);
  staticGroup.traverse((o) => { o.matrixAutoUpdate = false; });
  scene.updateMatrixWorld(true);

  // ============ waypoints ============
  const WPS: [number, number][] = MAP === "bazaar" ? [
    [0, 44], [-14, 38], [14, 38], [-34, 38], [34, 38],
    [0, 28], [3, 14], [3, 0], [0, -8], [0, -16], [-3, -26],
    [-12, 16], [-12, -2], [12, 16], [12, -2],
    [-20, 8], [-34, 8], [-34, -8], [-20, -19], [-30, -26], [-16, -34],
    [20, 8], [34, 8], [34, -8], [20, -19], [30, -26], [16, -34],
    [0, -42], [-20, -44], [20, -44], [-36, -40], [36, -40],
  ] : [
    // COMPOUND: south spawn yard, central building, side corridors, north sites
    [0, 44], [-14, 38], [14, 38], [-38, 38], [38, 38],
    [0, 30], [0, 18], [-24, 18], [24, 18],
    [0, 4], [0, -2], [0, -7],            // inside building
    [-17, -2], [17, -2],                  // building side doors
    [-24, -10], [24, -10],                // corridor mouths
    [-42, -10], [42, -10],                // outer corridors
    [-24, -26], [24, -26], [0, -20],
    [-28, -40], [28, -40], [0, -38], [-10, -46], [10, -46], [-42, -42], [42, -42],
  ];
  const wpLinks: number[][] = WPS.map(() => []);
  {
    const rc = new THREE.Raycaster();
    const dirV = new THREE.Vector3();
    for (let i = 0; i < WPS.length; i++) {
      for (let j = i + 1; j < WPS.length; j++) {
        const dx = WPS[j][0] - WPS[i][0], dz = WPS[j][1] - WPS[i][1];
        const d = Math.hypot(dx, dz);
        if (d > 23) continue;
        dirV.set(dx / d, 0, dz / d);
        rc.set(new THREE.Vector3(WPS[i][0], 1.1, WPS[i][1]), dirV);
        rc.far = d;
        if (rc.intersectObjects(staticGroup.children, false).length === 0) {
          wpLinks[i].push(j); wpLinks[j].push(i);
        }
      }
    }
  }
  function nearestWp(x: number, z: number): number {
    let best = 0, bd = 1e9;
    for (let i = 0; i < WPS.length; i++) {
      const d = (WPS[i][0] - x) ** 2 + (WPS[i][1] - z) ** 2;
      if (d < bd) { bd = d; best = i; }
    }
    return best;
  }
  function findPath(fx: number, fz: number, tx: number, tz: number): number[] {
    const a = nearestWp(fx, fz), b = nearestWp(tx, tz);
    if (a === b) return [b];
    const prev = new Array(WPS.length).fill(-1);
    const q = [a]; prev[a] = a;
    while (q.length) {
      const n = q.shift()!;
      if (n === b) break;
      for (const m of wpLinks[n]) if (prev[m] === -1) { prev[m] = n; q.push(m); }
    }
    if (prev[b] === -1) return [b];
    const path = [b]; let cur = b;
    while (cur !== a) { cur = prev[cur]; path.push(cur); }
    return path.reverse();
  }

  // first-person shadow: invisible proxy that casts the player's shadow
  const shadowProxy = new THREE.Group();
  {
    const spMat = new THREE.MeshBasicMaterial({ colorWrite: false, depthWrite: false });
    const torsoP = new THREE.Mesh(new THREE.CapsuleGeometry(0.28, 0.85, 3, 8), spMat);
    torsoP.position.y = 1.05; torsoP.castShadow = true;
    const headP = new THREE.Mesh(new THREE.SphereGeometry(0.15, 8, 6), spMat);
    headP.position.y = 1.7; headP.castShadow = true;
    shadowProxy.add(torsoP, headP);
    scene.add(shadowProxy);
  }

  // ============ player state ============
  const P = {
    pos: new THREE.Vector3(0, 0, 44), vel: new THREE.Vector3(),
    yaw: 0, pitch: 0, h: 1.8, onGround: true, crouching: false,
    hp: 100, armor: 0, money: 800, alive: true, kills: 0, deaths: 0,
  };
  const ST = { shots: 0, hits: 0, head: 0, streak: 0, bestStreak: 0 };
  // haptics: gamepad dual-rumble when available (mouse players get screen/audio feel)
  function rumble(strong: number, weak: number, ms: number) {
    try {
      const pads = navigator.getGamepads ? navigator.getGamepads() : [];
      for (const gp of pads) {
        const act = gp ? (gp as Gamepad & { vibrationActuator?: { playEffect?: (t: string, p: Record<string, number>) => void } }).vibrationActuator : null;
        if (act?.playEffect) act.playEffect("dual-rumble", { duration: ms, strongMagnitude: clamp(strong, 0, 1), weakMagnitude: clamp(weak, 0, 1) });
      }
    } catch { /* no gamepad */ }
  }
  const keys = new Set<string>();
  let mouseDown = false, semiQueue = false, boardOpen = false, buyOpen = false;
  let leanDir = 0, leanAmt = 0; // -1 left, +1 right (Z / X held)
  let locked = false;

  // weapons inventory
  const owned: Record<WeaponId, boolean> = { rifle: false, smg: false, sniper: false, pistol: true, knife: true };
  const ammo: Record<WeaponId, { mag: number; res: number }> = {
    rifle: { mag: 30, res: 90 }, smg: { mag: 30, res: 120 }, sniper: { mag: 5, res: 20 }, pistol: { mag: 12, res: 48 }, knife: { mag: 0, res: 0 },
  };
  let cur: WeaponId = "pistol";
  let fireT = 0, reloadT = 0, switchT = 0, recoilHeat = 0, knifeT = 0, gunKick = 0, bobT = 0, stepAcc = 0;
  let ads = false, adsAmt = 0, sprintAmt = 0, slideT = 0, slideCd = 0, landDip = 0, swayX = 0, swayY = 0;
  let inspectT = 0, stepPunch = 0;
  const INSPECT_LEN = 2.2;
  let frags = 1, smokes = 1, throwAnimT = 0, nadeSel: "frag" | "smoke" = "frag";
  let nadeOut = false; // grenade held as active "weapon" (CS-style slot 4)
  let bombOut = false; // C4 held in hands (slot 5, defuse attacking half)
  let nadeDrawT = 0;
  let lastGunWeapon: WeaponId = "pistol";
  // game-feel state
  let trauma = 0;            // 0..1, shake = trauma^2 (GDC screen-shake standard)
  let hitStopT = 0;          // brief time dilation on kill
  let killFlashT = 0;        // crosshair kill confirm
  let lastKillT = -10, multiKills = 0;
  let aimPunchP = 0, aimPunchY = 0; // camera jolt when taking damage
  const addTrauma = (x: number) => { trauma = clamp(trauma + x, 0, 1); };

  // ============ round state ============
  let phase: "buy" | "live" | "end" = "end";
  // ---- defuse mode state ----
  const SITES: Record<"A" | "B", { x: number; z: number; r: number }> = MAP === "bazaar"
    ? { A: { x: -32, z: -6, r: 7 }, B: { x: 32, z: -6, r: 7 } }
    : { A: { x: -27, z: -42, r: 7 }, B: { x: 27, z: -42, r: 7 } };
  const PLANT_LEN = 3.6, DEFUSE_LEN = 7, DEFUSE_KIT_LEN = 3.5, BOMB_LEN = 40;
  let attacking = true;          // player side this half: true = plant, false = defend
  let bombState: "idle" | "carried" | "dropped" | "planted" | "exploded" | "defused" = "idle";
  let bombX = 0, bombZ = 0, bombT = 0, bombSite: "A" | "B" = "A";
  let playerCarrier = false, playerPlantT = 0, playerDefuseT = 0;
  let hasKit = false;
  let beepAcc = 0;
  let phaseT = 0.01, round = 0, myScore = 0, enemyScore = 0;
  let gameT = 0;
  let matchOver = false, dmTime = DM_TIME;
  let lossStreak = 0, roundKillsMe = 0;
  const roundKillsBot = new Map<string, number>();
  let dmLimit = clamp(Math.round(opts.roundTarget && mp ? opts.roundTarget : DM_LIMIT), 5, 100);
  // ---- online warmup lobby ----
  let warmup = mp;
  let myReady = false;
  const readySet = new Set<string>();   // host: who is ready
  const rmVotes = new Set<string>();    // rematch votes (host counts)
  let lobbyAcc = 0;
  function saveProfile(won: boolean) {
    try {
      const raw = localStorage.getItem("sp-profile");
      const p = raw ? JSON.parse(raw) : {};
      p.kills = (p.kills || 0) + P.kills;
      p.deaths = (p.deaths || 0) + P.deaths;
      p.shots = (p.shots || 0) + ST.shots;
      p.hits = (p.hits || 0) + ST.hits;
      p.head = (p.head || 0) + ST.head;
      p.matches = (p.matches || 0) + 1;
      p.wins = (p.wins || 0) + (won ? 1 : 0);
      p.bestStreak = Math.max(p.bestStreak || 0, ST.bestStreak);
      localStorage.setItem("sp-profile", JSON.stringify(p));
    } catch { /* private mode */ }
  }

  // ============ audio ============
  let actx: AudioContext | null = null;
  let noiseBuf: AudioBuffer | null = null;
  let master: GainNode | null = null;
  function audioInit() {
    if (actx) { if (actx.state === "suspended") actx.resume(); return; }
    const AC: typeof AudioContext = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    actx = new AC();
    master = actx.createGain();
    master.gain.value = 0.5 * VOL;
    master.connect(actx.destination);
    const len = Math.floor(actx.sampleRate * 0.5);
    noiseBuf = actx.createBuffer(1, len, actx.sampleRate);
    const ch = noiseBuf.getChannelData(0);
    for (let i = 0; i < len; i++) ch[i] = Math.random() * 2 - 1;
    // ambient wind bed: looped noise -> bandpass -> slow LFO on gain
    const wind = actx.createBufferSource();
    wind.buffer = noiseBuf; wind.loop = true;
    const wf = actx.createBiquadFilter(); wf.type = "bandpass"; wf.frequency.value = 280; wf.Q.value = 0.4;
    const wg = actx.createGain(); wg.gain.value = 0.045;
    const lfo = actx.createOscillator(); lfo.frequency.value = 0.08;
    const lfoG = actx.createGain(); lfoG.gain.value = 0.02;
    lfo.connect(lfoG); lfoG.connect(wg.gain);
    wind.connect(wf); wf.connect(wg); wg.connect(master);
    wind.start(); lfo.start();
    loadSamples();
  }
  // --- real CC0 samples (Kenney RPG audio + little-big-shooter pack), decoded lazily ---
  const sampleBufs = new Map<string, AudioBuffer>();
  const SAMPLES: Record<string, string> = {
    shot_rifle: "/sfx/ak47_shot.wav", shot_single: "/sfx/single-shot.mp3", shot_burst: "/sfx/burst.mp3",
    reload: "/sfx/reload.mp3", empty: "/sfx/empty.mp3", hurt: "/sfx/hurt.mp3",
    step0: "/sfx/footstep_0.ogg", step1: "/sfx/footstep_1.ogg", step2: "/sfx/footstep_2.ogg",
    step3: "/sfx/footstep_3.ogg", step4: "/sfx/footstep_4.ogg",
    click: "/sfx/metal_click.ogg", latch: "/sfx/metal_latch.ogg",
    knife_draw: "/sfx/draw_knife_1.ogg", knife_slice: "/sfx/knife_slice.ogg",
    cloth: "/sfx/cloth_1.ogg", belt: "/sfx/belt_handle_1.ogg", clank: "/sfx/metal_pot_1.ogg",
  };
  function loadSamples() {
    if (!actx) return;
    Object.entries(SAMPLES).forEach(([key, url]) => {
      if (sampleBufs.has(key)) return;
      fetch(url).then((r) => r.arrayBuffer()).then((ab) => actx!.decodeAudioData(ab))
        .then((buf) => { sampleBufs.set(key, buf); })
        .catch(() => { /* fallback synth remains */ });
    });
  }
  function playSample(key: string, vol = 1, pan = 0, rate = 1, delay = 0): boolean {
    if (!actx || !master) return false;
    const buf = sampleBufs.get(key);
    if (!buf) return false;
    const t0 = actx.currentTime + delay;
    const src = actx.createBufferSource();
    src.buffer = buf;
    src.playbackRate.value = rate;
    const g = actx.createGain();
    g.gain.value = vol;
    src.connect(g);
    if (pan !== 0 && actx.createStereoPanner) {
      const p = actx.createStereoPanner(); p.pan.value = clamp(pan, -1, 1);
      g.connect(p); p.connect(master);
    } else g.connect(master);
    src.start(t0);
    return true;
  }
  function burst(dur: number, freq: number, vol: number, delay = 0, pan = 0) {
    if (!actx || !noiseBuf || !master) return;
    const t0 = actx.currentTime + delay;
    const src = actx.createBufferSource(); src.buffer = noiseBuf;
    const f = actx.createBiquadFilter(); f.type = "lowpass"; f.frequency.value = freq;
    const g = actx.createGain();
    g.gain.setValueAtTime(vol, t0);
    g.gain.exponentialRampToValueAtTime(0.001, t0 + dur);
    src.connect(f); f.connect(g);
    if (pan !== 0 && actx.createStereoPanner) {
      const p = actx.createStereoPanner(); p.pan.value = clamp(pan, -1, 1);
      g.connect(p); p.connect(master);
    } else g.connect(master);
    src.start(t0); src.stop(t0 + dur + 0.02);
  }
  // pan/attenuation from a world position relative to the camera
  const panRight = new THREE.Vector3();
  function panVol(pos: THREE.Vector3): { pan: number; mul: number } {
    const dx = pos.x - camera.position.x, dz = pos.z - camera.position.z;
    const dist = Math.hypot(dx, dz);
    panRight.set(1, 0, 0).applyQuaternion(camera.quaternion);
    const pan = clamp((dx * panRight.x + dz * panRight.z) / Math.max(dist, 0.001), -1, 1) * 0.7;
    return { pan, mul: clamp(1.15 - dist / 45, 0, 1) };
  }
  function blip(freq: number, dur: number, vol: number, type: OscillatorType = "square", delay = 0) {
    if (!actx || !master) return;
    const t0 = actx.currentTime + delay;
    const o = actx.createOscillator(); o.type = type; o.frequency.value = freq;
    const g = actx.createGain();
    g.gain.setValueAtTime(vol, t0);
    g.gain.exponentialRampToValueAtTime(0.001, t0 + dur);
    o.connect(g); g.connect(master);
    o.start(t0); o.stop(t0 + dur + 0.02);
  }
  const sShot = (w: WeaponId, vol = 1, pan = 0) => {
    if (w === "rifle") {
      if (!playSample("shot_rifle", 0.5 * vol, pan, rand(0.96, 1.04))) { burst(0.17, 1300, 0.55 * vol, 0, pan); blip(105, 0.07, 0.4 * vol); }
      burst(0.3, 420, 0.08 * vol, 0.12, pan); // distant tail layer
    } else if (w === "smg") {
      if (!playSample("shot_single", 0.42 * vol, pan, rand(1.18, 1.28))) { burst(0.11, 1650, 0.38 * vol, 0, pan); blip(130, 0.05, 0.3 * vol); }
    } else if (w === "pistol") {
      if (!playSample("shot_single", 0.5 * vol, pan, rand(0.95, 1.05))) { burst(0.13, 1900, 0.45 * vol, 0, pan); blip(120, 0.05, 0.32 * vol); }
    } else if (w === "sniper") {
      if (!playSample("shot_rifle", 0.72 * vol, pan, 0.72)) { burst(0.32, 950, 0.78 * vol, 0, pan); blip(72, 0.15, 0.5 * vol); }
      burst(0.55, 480, 0.22 * vol, 0.06, pan); burst(0.7, 360, 0.1 * vol, 0.22, pan);
    } else {
      if (!playSample("knife_slice", 0.45 * vol, pan, rand(0.95, 1.1))) burst(0.08, 3200, 0.18 * vol);
    }
  };
  const sStep = (vol = 0.09, pan = 0, muffled = false) => {
    const k = "step" + Math.floor(rand(0, 5));
    const rate = muffled ? rand(0.6, 0.7) : rand(0.92, 1.1); // slower playback ~ darker / through-wall feel
    if (!playSample(k, vol * 3.2 * (muffled ? 0.55 : 1), pan, rate)) burst(rand(0.04, 0.06), muffled ? rand(200, 300) : rand(460, 700), vol * rand(0.8, 1.2) * (muffled ? 0.5 : 1), 0, pan);
  };
  const sHitmark = () => blip(1244, 0.045, 0.22, "sine");
  const sHurt = () => { if (!playSample("hurt", 0.5, 0, rand(0.95, 1.05))) { blip(150, 0.13, 0.45, "sawtooth"); burst(0.1, 700, 0.3); } };
  const sClick = () => { if (!playSample("click", 0.4, 0, rand(0.95, 1.1))) blip(900, 0.03, 0.18); };
  const sEmpty = () => { if (!playSample("empty", 0.5)) sClick(); };
  const sReload = () => {
    if (!playSample("reload", 0.55)) { blip(620, 0.04, 0.22, "square", 0); blip(760, 0.04, 0.22, "square", 0.45); blip(980, 0.05, 0.25, "square", 1.0); }
    playSample("belt", 0.35, 0, 1, 0.1);
  };
  const sDraw = (melee: boolean) => {
    if (melee) playSample("knife_draw", 0.5);
    else { playSample("cloth", 0.4, 0, 1.15); playSample("latch", 0.3, 0, rand(0.95, 1.1), 0.1); }
  };
  const sBuy = () => { blip(880, 0.06, 0.25, "sine"); blip(1320, 0.08, 0.25, "sine", 0.07); };
  const sWin = () => { blip(523, 0.12, 0.3, "triangle"); blip(659, 0.12, 0.3, "triangle", 0.13); blip(784, 0.22, 0.3, "triangle", 0.26); };
  const sLose = () => { blip(392, 0.16, 0.3, "triangle"); blip(311, 0.16, 0.3, "triangle", 0.18); blip(233, 0.3, 0.3, "triangle", 0.36); };

  // ============ FX ============
  const dotCv = document.createElement("canvas");
  dotCv.width = dotCv.height = 32;
  {
    const c = dotCv.getContext("2d")!;
    const gr = c.createRadialGradient(16, 16, 1, 16, 16, 15);
    gr.addColorStop(0, "rgba(255,255,255,1)");
    gr.addColorStop(1, "rgba(255,255,255,0)");
    c.fillStyle = gr; c.fillRect(0, 0, 32, 32);
  }
  const dotTex = new THREE.CanvasTexture(dotCv);
  const spriteMats = new Map<number, THREE.SpriteMaterial>();
  function spriteMat(color: number): THREE.SpriteMaterial {
    let m = spriteMats.get(color);
    if (!m) { m = new THREE.SpriteMaterial({ map: dotTex, color, transparent: true, depthWrite: false }); spriteMats.set(color, m); }
    return m;
  }
  interface Particle { s: THREE.Sprite; v: THREE.Vector3; ttl: number; max: number; g: number }
  const particles: Particle[] = [];
  function spawnParticles(pos: THREE.Vector3, n: number, color: number, speed: number, size: number, ttl: number, grav = 9) {
    if (particles.length > 260) return;
    for (let i = 0; i < n; i++) {
      const s = new THREE.Sprite(spriteMat(color).clone());
      s.scale.setScalar(size * rand(0.7, 1.4));
      s.position.copy(pos);
      scene.add(s);
      particles.push({
        s, ttl, max: ttl, g: grav,
        v: new THREE.Vector3(rand(-1, 1), rand(-0.2, 1.2), rand(-1, 1)).normalize().multiplyScalar(speed * rand(0.4, 1.3)),
      });
    }
  }
  interface Tracer { l: THREE.Line; ttl: number; max: number }
  const tracers: Tracer[] = [];
  function addTracer(a: THREE.Vector3, b: THREE.Vector3, color: number, ttl = 0.1) {
    const g = new THREE.BufferGeometry().setFromPoints([a, b]);
    const m = new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.85, blending: THREE.AdditiveBlending });
    const l = new THREE.Line(g, m);
    scene.add(l);
    tracers.push({ l, ttl, max: ttl });
  }
  const decals: THREE.Mesh[] = [];
  const decalMat = new THREE.MeshBasicMaterial({ color: 0x1a1410, transparent: true, opacity: 0.75, polygonOffset: true, polygonOffsetFactor: -2 });
  const decalGeo = new THREE.CircleGeometry(0.055, 8);
  function addDecal(point: THREE.Vector3, normal: THREE.Vector3) {
    const d = new THREE.Mesh(decalGeo, decalMat);
    d.position.copy(point).addScaledVector(normal, 0.012);
    d.lookAt(point.clone().add(normal));
    scene.add(d);
    decals.push(d);
    if (decals.length > 80) { const old = decals.shift()!; scene.remove(old); }
  }
  const flashLight = new THREE.PointLight(0xffc66e, 0, 10);
  scene.add(flashLight);
  // textured star flash quad (three-fps style: random rotation + scale per shot)
  const flashTex = (() => {
    const cv = document.createElement("canvas"); cv.width = cv.height = 64;
    const c = cv.getContext("2d")!;
    c.translate(32, 32);
    const grad = c.createRadialGradient(0, 0, 1, 0, 0, 30);
    grad.addColorStop(0, "rgba(255,255,235,1)");
    grad.addColorStop(0.35, "rgba(255,205,110,0.9)");
    grad.addColorStop(1, "rgba(255,150,40,0)");
    c.fillStyle = grad;
    for (let i = 0; i < 6; i++) {
      c.rotate(Math.PI / 3);
      c.beginPath();
      c.ellipse(0, 0, 30, 7 + Math.random() * 4, 0, 0, 7);
      c.fill();
    }
    const t = new THREE.CanvasTexture(cv);
    t.colorSpace = THREE.SRGBColorSpace;
    return t;
  })();
  const flashPool: THREE.Sprite[] = [];
  for (let i = 0; i < 6; i++) {
    const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: flashTex, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, rotation: 0 }));
    sp.visible = false;
    scene.add(sp);
    flashPool.push(sp);
  }
  let flashIdx = 0;
  const flashLives = new Float32Array(6);
  function muzzleFlashAt(pos: THREE.Vector3, big: boolean) {
    flashLight.position.copy(pos);
    flashLight.intensity = big ? 9 : 5;
    const sp = flashPool[flashIdx];
    flashLives[flashIdx] = 0.05;
    flashIdx = (flashIdx + 1) % flashPool.length;
    sp.position.copy(pos);
    sp.material.rotation = Math.random() * Math.PI * 2;
    sp.material.opacity = 1;
    sp.scale.setScalar((big ? 0.5 : 0.32) * rand(0.8, 1.5));
    sp.visible = true;
    spawnParticles(pos, 2, 0xffd27a, 1.2, 0.14, 0.05, 0);
  }
  function updateFX(dt: number) {
    flashLight.intensity = Math.max(0, flashLight.intensity - 90 * dt);
    for (let i = 0; i < flashPool.length; i++) {
      if (flashLives[i] > 0) {
        flashLives[i] -= dt;
        flashPool[i].material.opacity = Math.max(0, flashLives[i] / 0.05);
        if (flashLives[i] <= 0) flashPool[i].visible = false;
      }
    }
    for (let i = particles.length - 1; i >= 0; i--) {
      const p = particles[i];
      p.ttl -= dt;
      if (p.ttl <= 0) { scene.remove(p.s); p.s.material.dispose(); particles.splice(i, 1); continue; }
      p.v.y -= p.g * dt;
      p.s.position.addScaledVector(p.v, dt);
      p.s.material.opacity = p.ttl / p.max;
    }
    for (let i = tracers.length - 1; i >= 0; i--) {
      const tr = tracers[i];
      tr.ttl -= dt;
      if (tr.ttl <= 0) {
        scene.remove(tr.l); tr.l.geometry.dispose(); (tr.l.material as THREE.Material).dispose();
        tracers.splice(i, 1); continue;
      }
      (tr.l.material as THREE.LineBasicMaterial).opacity = 0.85 * (tr.ttl / tr.max);
    }
  }

  // ============ viewmodels ============
  const vmRoot = new THREE.Group();
  camera.add(vmRoot);
  function vmBox(parent: THREE.Group, w: number, h: number, d: number, x: number, y: number, z: number, color: number, rz = 0) {
    const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), new THREE.MeshStandardMaterial({ color, roughness: 0.55, metalness: 0.35 }));
    m.position.set(x, y, z); m.rotation.z = rz;
    parent.add(m);
    return m;
  }
  function vmCyl(parent: THREE.Group, r1: number, r2: number, len: number, x: number, y: number, z: number, color: number, rx = Math.PI / 2, metal = 0.5) {
    const m = new THREE.Mesh(new THREE.CylinderGeometry(r1, r2, len, 10), new THREE.MeshStandardMaterial({ color, roughness: 0.45, metalness: metal }));
    m.position.set(x, y, z); m.rotation.x = rx;
    parent.add(m);
    return m;
  }
  const wskinMat = new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.42, metalness: 0.5 });
  texLoader.load("/tex/wskin.jpg", (t) => {
    t.colorSpace = THREE.SRGBColorSpace; t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(0.6, 0.6);
    wskinMat.map = t; wskinMat.needsUpdate = true;
  });
  function vmSkinBox(parent: THREE.Group, w: number, h: number, d: number, x: number, y: number, z: number, rz = 0) {
    const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), wskinMat);
    m.position.set(x, y, z); m.rotation.z = rz;
    parent.add(m);
    return m;
  }
  const handMat = new THREE.MeshStandardMaterial({ color: 0x9c7b58, roughness: 0.85 });
  const gloveMat = new THREE.MeshStandardMaterial({ color: 0x2e3236, roughness: 0.9 });
  const sleeveMat = new THREE.MeshStandardMaterial({ color: 0x5b6470, roughness: 0.92 });
  texLoader.load("/tex/camo.jpg", (t) => { t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(0.8, 0.8); sleeveMat.map = t; sleeveMat.color.set(0xb9c2cc); sleeveMat.needsUpdate = true; });
  function vmHand(parent: THREE.Group, x: number, y: number, z: number, rx: number, rz: number) {
    const hand = new THREE.Group();
    const palm = new THREE.Mesh(new THREE.BoxGeometry(0.075, 0.035, 0.1), gloveMat);
    hand.add(palm);
    for (let i = 0; i < 4; i++) {
      const f = new THREE.Mesh(new THREE.BoxGeometry(0.016, 0.016, 0.05), handMat);
      f.position.set(-0.027 + i * 0.018, 0.012, -0.065);
      f.rotation.x = -0.5;
      hand.add(f);
    }
    const thumb = new THREE.Mesh(new THREE.BoxGeometry(0.016, 0.016, 0.045), handMat);
    thumb.position.set(0.045, 0.005, -0.02); thumb.rotation.y = -0.6;
    hand.add(thumb);
    const wrist = new THREE.Mesh(new THREE.BoxGeometry(0.07, 0.06, 0.12), new THREE.MeshStandardMaterial({ color: 0x4a4438, roughness: 0.95 }));
    wrist.position.set(0, -0.01, 0.09);
    hand.add(wrist);
    hand.position.set(x, y, z);
    hand.rotation.set(rx, 0, rz);
    parent.add(hand);
    // arm: slim tapered cylinder running from the wrist to a shoulder anchor at the screen's lower corner.
    // Built by direction (wrist -> anchor) in weapon-group space, so per-grip hand rotations can't bend it.
    const isLeft = rz >= 0.5; // support hands use rz ~0.5..1.5
    const armG = new THREE.Group(); // Group so dressVM's GLB swap keeps it
    const wristP = new THREE.Vector3(x, y - 0.015, z + 0.07);
    const shoulderP = new THREE.Vector3(isLeft ? -0.26 : 0.15, -0.55, isLeft ? 0.38 : 0.5);
    const dirV = new THREE.Vector3().subVectors(wristP, shoulderP);
    const dirN = dirV.clone().normalize();
    const arm = new THREE.Mesh(new THREE.CylinderGeometry(0.036, 0.06, dirV.length(), 10), sleeveMat);
    arm.position.copy(shoulderP).addScaledVector(dirV, 0.5);
    arm.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), dirN);
    armG.add(arm);
    const cuff2 = new THREE.Mesh(new THREE.CylinderGeometry(0.042, 0.047, 0.07, 10), gloveMat);
    cuff2.position.copy(wristP).addScaledVector(dirN, -0.045);
    cuff2.quaternion.copy(arm.quaternion);
    armG.add(cuff2);
    parent.add(armG);
    return hand;
  }
  function buildVM(id: WeaponId): THREE.Group {
    const g = new THREE.Group();
    const muzzle = new THREE.Object3D();
    if (id === "rifle") {
      // AK-pattern: receiver, dust cover, wood handguard, long barrel, gas tube, curved mag, stock, iron sights
      vmSkinBox(g, 0.066, 0.075, 0.34, 0, 0.005, -0.06);                  // receiver (teal skin)
      vmBox(g, 0.06, 0.03, 0.3, 0, 0.052, -0.06, 0x3c3a34);              // dust cover
      vmSkinBox(g, 0.07, 0.075, 0.2, 0, 0.005, -0.32);                    // skinned handguard
      vmCyl(g, 0.016, 0.016, 0.36, 0, 0.005, -0.56, 0x2a2a2a);           // barrel
      vmCyl(g, 0.011, 0.011, 0.3, 0, 0.05, -0.5, 0x3a3a3a);              // gas tube
      vmCyl(g, 0.02, 0.024, 0.05, 0, 0.005, -0.745, 0x222222);           // muzzle brake
      const mag = vmBox(g, 0.042, 0.2, 0.075, 0, -0.12, -0.13, 0x453a26); mag.rotation.x = 0.45; // curved mag
      vmBox(g, 0.045, 0.1, 0.05, 0, -0.085, 0.06, 0x59401f, 0.25);       // grip
      vmBox(g, 0.05, 0.06, 0.22, 0, -0.01, 0.21, 0x6e4f28);              // stock
      vmBox(g, 0.05, 0.075, 0.03, 0, -0.02, 0.325, 0x4a3518);            // buttpad
      vmBox(g, 0.012, 0.035, 0.012, 0, 0.075, -0.71, 0x1a1a1a);          // front post
      vmBox(g, 0.04, 0.022, 0.02, 0, 0.068, -0.12, 0x1a1a1a);            // rear sight
      vmHand(g, 0, -0.05, -0.31, 0.2, 1.5);                              // support hand under handguard
      vmHand(g, 0.012, -0.115, 0.075, -0.25, 0);                         // trigger hand on grip
      muzzle.position.set(0, 0.005, -0.78);
    } else if (id === "smg") {
      vmSkinBox(g, 0.062, 0.085, 0.26, 0, 0, -0.04);                      // receiver (teal skin)
      vmBox(g, 0.05, 0.04, 0.18, 0, 0.055, -0.05, 0x24282c);             // top rail
      for (let i = 0; i < 5; i++) vmBox(g, 0.052, 0.006, 0.012, 0, 0.078, -0.11 + i * 0.03, 0x1c1f22); // rail teeth
      vmCyl(g, 0.014, 0.014, 0.2, 0, 0.005, -0.27, 0x222426);            // barrel
      vmCyl(g, 0.022, 0.022, 0.11, 0, 0.005, -0.31, 0x191b1d);           // suppressor
      vmBox(g, 0.036, 0.17, 0.05, 0, -0.115, -0.06, 0x202327, 0.08);     // mag
      vmBox(g, 0.042, 0.1, 0.05, 0, -0.08, 0.07, 0x2a2d31, 0.2);         // grip
      const stockBar = vmBox(g, 0.016, 0.016, 0.16, 0, 0.02, 0.18, 0x33363a); stockBar.rotation.x = 0;
      vmBox(g, 0.05, 0.07, 0.025, 0, 0.0, 0.265, 0x2a2d31);              // stock plate
      vmBox(g, 0.012, 0.03, 0.012, 0, 0.075, -0.3, 0x121416);            // front sight
      vmHand(g, 0, -0.05, -0.13, 0.25, 1.5);
      vmHand(g, 0.012, -0.105, 0.085, -0.25, 0);
      muzzle.position.set(0, 0.005, -0.38);
    } else if (id === "pistol") {
      vmBox(g, 0.045, 0.05, 0.21, 0, 0.012, -0.04, 0x34383d);            // slide
      for (let i = 0; i < 4; i++) vmBox(g, 0.048, 0.03, 0.008, 0, 0.012, 0.045 + i * 0.014, 0x2a2e33); // slide serrations
      vmBox(g, 0.04, 0.035, 0.16, 0, -0.022, -0.03, 0x2c2f33);           // frame
      vmCyl(g, 0.01, 0.01, 0.04, 0, 0.012, -0.16, 0x1c1e21);             // barrel tip
      vmBox(g, 0.04, 0.12, 0.05, 0.004, -0.095, 0.045, 0x26282c, 0.16);  // grip
      vmBox(g, 0.026, 0.05, 0.03, 0, -0.045, -0.045, 0x202225);          // trigger guard
      vmBox(g, 0.01, 0.026, 0.01, 0, 0.048, -0.135, 0x111315);           // front sight
      vmBox(g, 0.034, 0.02, 0.014, 0, 0.046, 0.08, 0x111315);            // rear sight
      vmHand(g, 0.006, -0.12, 0.06, -0.3, 0);
      vmHand(g, -0.025, -0.115, 0.03, -0.25, 0.5);                       // support hand wrap
      muzzle.position.set(0, 0.012, -0.19);
    } else if (id === "sniper") {
      vmBox(g, 0.06, 0.08, 0.4, 0, 0, -0.08, 0x2c3a30);
      vmCyl(g, 0.015, 0.015, 0.55, 0, 0.005, -0.62, 0x232523);
      vmCyl(g, 0.026, 0.026, 0.09, 0, 0.005, -0.91, 0x1a1c1a);
      vmCyl(g, 0.028, 0.028, 0.2, 0, 0.085, -0.12, 0x181a1c);
      vmCyl(g, 0.035, 0.035, 0.045, 0, 0.085, -0.235, 0x101214);
      vmCyl(g, 0.032, 0.032, 0.04, 0, 0.085, -0.005, 0x101214);
      vmBox(g, 0.016, 0.05, 0.025, 0, 0.05, -0.12, 0x202422);
      vmBox(g, 0.04, 0.15, 0.06, 0, -0.105, -0.02, 0x232a26, 0.1);
      vmBox(g, 0.045, 0.09, 0.05, 0, -0.08, 0.1, 0x2c3a30, 0.2);
      vmBox(g, 0.05, 0.07, 0.24, 0, -0.005, 0.26, 0x26332b);
      vmBox(g, 0.05, 0.1, 0.03, 0, -0.03, 0.39, 0x1d2722);
      const bolt = vmCyl(g, 0.009, 0.009, 0.07, 0.05, 0.025, 0.03, 0xb9bec4); bolt.rotation.z = -0.9;
      vmHand(g, 0, -0.05, -0.35, 0.2, 1.5);
      vmHand(g, 0.012, -0.105, 0.115, -0.25, 0);
      muzzle.position.set(0, 0.005, -0.95);
    } else {
      // tanto knife
      const blade = new THREE.Mesh(new THREE.BoxGeometry(0.012, 0.05, 0.26), new THREE.MeshStandardMaterial({ color: 0xb8bdc4, roughness: 0.25, metalness: 0.9 }));
      blade.position.set(0, 0.01, -0.16);
      g.add(blade);
      const edge = new THREE.Mesh(new THREE.BoxGeometry(0.004, 0.014, 0.24), new THREE.MeshStandardMaterial({ color: 0xe8ecf0, roughness: 0.15, metalness: 1 }));
      edge.position.set(0, -0.018, -0.15);
      g.add(edge);
      const tip = new THREE.Mesh(new THREE.ConeGeometry(0.025, 0.06, 4), new THREE.MeshStandardMaterial({ color: 0xb8bdc4, roughness: 0.25, metalness: 0.9 }));
      tip.rotation.x = -Math.PI / 2; tip.scale.set(0.45, 1, 0.9);
      tip.position.set(0, 0.005, -0.315);
      g.add(tip);
      vmBox(g, 0.035, 0.055, 0.03, 0, 0, -0.025, 0x3a342c);              // guard
      vmBox(g, 0.032, 0.045, 0.13, 0, -0.005, 0.055, 0x23211d);          // handle
      vmHand(g, 0.005, -0.045, 0.06, -0.2, 0.2);
      muzzle.position.set(0, 0, -0.34);
    }
    g.add(muzzle);
    g.userData.muzzle = muzzle;
    g.userData.base = new THREE.Vector3(0.24, -0.21, -0.42);
    g.userData.adsPos =
      id === "rifle" ? new THREE.Vector3(0, -0.13, -0.3)
      : id === "smg" ? new THREE.Vector3(0, -0.13, -0.26)
      : id === "pistol" ? new THREE.Vector3(0, -0.11, -0.22)
      : id === "sniper" ? new THREE.Vector3(0, -0.1, -0.3)
      : new THREE.Vector3(0.24, -0.21, -0.42);
    g.traverse((o) => { if (o instanceof THREE.Mesh) { o.castShadow = false; o.receiveShadow = false; } });
    g.position.copy(g.userData.base as THREE.Vector3);
    vmRoot.add(g);
    return g;
  }
  const vms: Record<WeaponId, THREE.Group> = {
    rifle: buildVM("rifle"), smg: buildVM("smg"), sniper: buildVM("sniper"), pistol: buildVM("pistol"), knife: buildVM("knife"),
  };
  // --- swap procedural box guns for real GLB models once loaded (Quaternius kit) ---
  // GLB guns point +X with grip near origin; rotate to -Z forward, scale by real length.
  const VM_GLB: Partial<Record<WeaponId, { file: string; len: number; pos: [number, number, number]; muzzle: [number, number, number]; adsPos: [number, number, number] }>> = {
    rifle:  { file: "ak47",   len: 0.78, pos: [0, -0.04, -0.18], muzzle: [0, 0.0, -0.62],  adsPos: [0, -0.135, -0.3] },
    smg:    { file: "smg",    len: 0.62, pos: [0, -0.04, -0.14], muzzle: [0, 0.0, -0.48],  adsPos: [0, -0.13, -0.26] },
    sniper: { file: "sniper", len: 1.0,  pos: [0, -0.04, -0.22], muzzle: [0, 0.0, -0.78],  adsPos: [0, -0.1, -0.32] },
    pistol: { file: "pistol", len: 0.34, pos: [0, -0.05, -0.08], muzzle: [0, 0.0, -0.28],  adsPos: [0, -0.115, -0.2] },
  };
  function dressVM(w: WeaponId) {
    const spec = VM_GLB[w];
    if (!spec) return;
    gltfLoader.load("/models/" + spec.file + ".glb", (g) => {
      const vm = vms[w];
      // remove procedural meshes except hands (keep both vmHand groups) and muzzle Object3D
      const keep = new Set<THREE.Object3D>();
      const muzzleO = vm.userData.muzzle as THREE.Object3D;
      keep.add(muzzleO);
      vm.children.filter((c) => c instanceof THREE.Group).forEach((c) => keep.add(c)); // hands are Groups
      [...vm.children].forEach((c) => { if (!keep.has(c)) vm.remove(c); });
      const model = g.scene;
      const box = new THREE.Box3().setFromObject(model);
      const len = Math.max(box.max.x - box.min.x, 0.01);
      const sc = spec.len / len;
      model.scale.setScalar(sc);
      model.rotation.y = -Math.PI / 2; // +X barrel → -Z forward
      model.updateMatrixWorld(true);
      const box2 = new THREE.Box3().setFromObject(model);
      const cx = (box2.max.x + box2.min.x) / 2;
      const cy = (box2.max.y + box2.min.y) / 2;
      model.position.set(spec.pos[0] - cx, spec.pos[1] - cy, spec.pos[2] - (box2.max.z + box2.min.z) / 2);
      model.traverse((o) => {
        if (o instanceof THREE.Mesh) {
          o.castShadow = false; o.receiveShadow = false; o.frustumCulled = false;
          const m = o.material as THREE.MeshStandardMaterial;
          if (m && m.isMeshStandardMaterial) { m.roughness = Math.min(0.7, m.roughness); }
        }
      });
      vm.add(model);
      muzzleO.position.set(spec.muzzle[0], spec.muzzle[1], spec.muzzle[2]);
      vm.userData.adsPos = new THREE.Vector3(spec.adsPos[0], spec.adsPos[1], spec.adsPos[2]);
    });
  }
  (["rifle", "smg", "sniper", "pistol"] as WeaponId[]).forEach(dressVM);
  // grenade viewmodel: GLB frag in hand (also used for smoke, tinted)
  const vmNade = new THREE.Group();
  const nadeBodyHolder = new THREE.Group();
  vmNade.add(nadeBodyHolder);
  vmHand(vmNade, 0.012, -0.045, 0.03, -0.3, 0.25);
  vmNade.userData.base = new THREE.Vector3(0.26, -0.24, -0.38);
  vmNade.userData.adsPos = vmNade.userData.base;
  vmNade.userData.muzzle = new THREE.Object3D();
  vmNade.add(vmNade.userData.muzzle as THREE.Object3D);
  vmNade.position.copy(vmNade.userData.base as THREE.Vector3);
  vmNade.visible = false;
  vmRoot.add(vmNade);
  const nadeTintMat = new THREE.MeshStandardMaterial({ color: 0x4a5560, roughness: 0.5, metalness: 0.4 });
  // C4 viewmodel (slot 5 while carrying)
  const vmC4 = new THREE.Group();
  {
    const body = new THREE.Mesh(new THREE.BoxGeometry(0.2, 0.07, 0.13), new THREE.MeshStandardMaterial({ color: 0x3a3a32, roughness: 0.6, metalness: 0.3 }));
    vmC4.add(body);
    const led2 = new THREE.Mesh(new THREE.BoxGeometry(0.03, 0.02, 0.03), new THREE.MeshStandardMaterial({ color: 0xff2222, emissive: 0xff0000, emissiveIntensity: 2 }));
    led2.position.set(0.05, 0.045, 0);
    vmC4.add(led2);
    const keypad = new THREE.Mesh(new THREE.BoxGeometry(0.08, 0.012, 0.06), new THREE.MeshStandardMaterial({ color: 0x8a9aa4, roughness: 0.4, metalness: 0.5 }));
    keypad.position.set(-0.03, 0.041, 0);
    vmC4.add(keypad);
    vmHand(vmC4, 0.012, -0.05, 0.04, -0.32, 0.22);
    vmC4.userData.base = new THREE.Vector3(0.25, -0.25, -0.36);
    vmC4.userData.adsPos = vmC4.userData.base;
    vmC4.userData.muzzle = new THREE.Object3D();
    vmC4.add(vmC4.userData.muzzle as THREE.Object3D);
    vmC4.position.copy(vmC4.userData.base as THREE.Vector3);
    vmC4.visible = false;
    vmRoot.add(vmC4);
  }
  // dotted trajectory preview while holding a grenade
  const ARC_N = 14;
  const arcDots: THREE.Sprite[] = [];
  {
    for (let i = 0; i < ARC_N; i++) {
      const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: dotTex, color: 0xffe9a8, transparent: true, opacity: 0, depthWrite: false }));
      sp.scale.setScalar(0.085);
      scene.add(sp);
      arcDots.push(sp);
    }
  }
  function updateNadeArc(show: boolean) {
    if (!show) { for (const d2 of arcDots) (d2.material as THREE.SpriteMaterial).opacity = 0; return; }
    const dir = new THREE.Vector3();
    camera.getWorldDirection(dir);
    const p = camera.getWorldPosition(new THREE.Vector3()).addScaledVector(dir, 0.4);
    let vx = dir.x * 14, vy = dir.y * 14 + 3.2, vz = dir.z * 14;
    const stepT = 0.07;
    for (let i = 0; i < ARC_N; i++) {
      vy -= 13 * stepT;
      p.x += vx * stepT; p.y += vy * stepT; p.z += vz * stepT;
      const gh = groundHeightAt(p.x, p.z) + 0.06;
      if (p.y < gh) p.y = gh;
      arcDots[i].position.copy(p);
      (arcDots[i].material as THREE.SpriteMaterial).opacity = 0.5 * (1 - i / ARC_N);
    }
  }
  gltfLoader.load("/models/grenade.glb", (g) => {
    const model = g.scene;
    const box = new THREE.Box3().setFromObject(model);
    const h = Math.max(box.max.y - box.min.y, box.max.z - box.min.z, 0.01);
    model.scale.setScalar(0.14 / h);
    const box2 = new THREE.Box3().setFromObject(model);
    model.position.set(-(box2.max.x + box2.min.x) / 2, -(box2.max.y + box2.min.y) / 2 - 0.03, -(box2.max.z + box2.min.z) / 2 - 0.02);
    nadeBodyHolder.add(model);
  });
  function refreshVM() {
    (Object.keys(vms) as WeaponId[]).forEach((k) => { vms[k].visible = !nadeOut && !bombOut && k === cur; });
    vmNade.visible = nadeOut;
    vmC4.visible = bombOut;
    nadeBodyHolder.traverse((o) => {
      if (o instanceof THREE.Mesh) {
        if (nadeSel === "smoke") { o.userData.origMat = o.userData.origMat || o.material; o.material = nadeTintMat; }
        else if (o.userData.origMat) { o.material = o.userData.origMat; }
      }
    });
  }
  refreshVM();


  // ============ GLB asset library (Quaternius CC0 — Toon Shooter Game Kit via poly.pizza) ============
  interface CharLib { scene: THREE.Object3D; clips: Map<string, THREE.AnimationClip>; scale: number; yOff: number }
  const charLib: { enemy?: CharLib; friend?: CharLib } = {};
  const gunLib: Partial<Record<WeaponId, THREE.Object3D>> = {};
  const WEAPON_NODE: Record<WeaponId, string> = { rifle: "AK", smg: "SMG", sniper: "Sniper", pistol: "Pistol", knife: "Knife_1" };
  const ALL_WEAPON_NODES = ["Revolver", "Sniper", "Revolver_Small", "Pistol", "SMG", "GrenadeLauncher", "ShortCannon", "Shotgun", "Sniper_2", "RocketLauncher", "AK", "Shovel", "Knife_2", "Knife_1"];
  function prepCharGLB(g: GLTF): CharLib {
    const sceneG = g.scene;
    const clips = new Map<string, THREE.AnimationClip>();
    for (const c of g.animations) {
      const n = c.name.includes("|") ? c.name.split("|").pop()! : c.name;
      if (!clips.has(n)) clips.set(n, c);
    }
    const box = new THREE.Box3().setFromObject(sceneG);
    const h = Math.max(0.01, box.max.y - box.min.y);
    const scale = 1.82 / h;
    return { scene: sceneG, clips, scale, yOff: -box.min.y * scale };
  }
  gltfLoader.load("/models/character-enemy.glb", (g) => { charLib.enemy = prepCharGLB(g); });
  gltfLoader.load("/models/character-hazmat.glb", (g) => { charLib.friend = prepCharGLB(g); });
  (["rifle", "smg", "sniper", "pistol"] as WeaponId[]).forEach((w) => {
    const file = w === "rifle" ? "ak47" : w === "smg" ? "smg" : w === "sniper" ? "sniper" : "pistol";
    gltfLoader.load("/models/" + file + ".glb", (g) => { gunLib[w] = g.scene; });
  });

  // map props from the kit: visual GLB + invisible box collider (bullets, movement, bot LOS)
  const proxyMat = new THREE.MeshBasicMaterial({ visible: false });
  function addProp(file: string, x: number, z: number, ry: number, targetLen: number, coll?: { w: number; d: number; h: number }) {
    gltfLoader.load("/models/" + file + ".glb", (g) => {
      const m = g.scene;
      const box = new THREE.Box3().setFromObject(m);
      const len = Math.max(box.max.x - box.min.x, box.max.z - box.min.z, 0.01);
      const sc = targetLen / len;
      m.scale.setScalar(sc);
      const box2 = new THREE.Box3().setFromObject(m);
      m.position.set(x - (box2.max.x + box2.min.x) / 2, -box2.min.y, z - (box2.max.z + box2.min.z) / 2);
      m.rotation.y = ry;
      m.traverse((o) => { if (o instanceof THREE.Mesh) { o.castShadow = true; o.receiveShadow = true; } });
      scene.add(m);
      m.updateMatrixWorld(true);
      m.traverse((o) => { o.matrixAutoUpdate = false; });
    });
    if (coll) {
      const collider = new THREE.Mesh(new THREE.BoxGeometry(coll.w, coll.h, coll.d), proxyMat);
      collider.position.set(x, coll.h / 2, z);
      collider.visible = false;
      staticGroup.add(collider);
      collider.updateMatrixWorld(true);
      collider.matrixAutoUpdate = false;
      walls.push(new THREE.Box3(new THREE.Vector3(x - coll.w / 2, 0, z - coll.d / 2), new THREE.Vector3(x + coll.w / 2, coll.h, z + coll.d / 2)));
      mapRects.push({ x, z, w: coll.w, d: coll.d });
    }
  }
  if (MAP === "bazaar") {
  // --- mid lane: barriers + cones funnel the main sightline ---
  addProp("barrier-large", -14, 24, 0, 3.6, { w: 3.6, d: 0.8, h: 2.2 });
  addProp("barrier-large", 14, 24, 0, 3.6, { w: 3.6, d: 0.8, h: 2.2 });
  addProp("barrier-fixed", 0, -2, Math.PI / 2, 3.4, { w: 0.9, d: 3.4, h: 1.1 });
  addProp("traffic-cone", -4.5, -30, 0, 0.55);
  addProp("traffic-cone", 4.2, 18.5, 0, 0.55);
  addProp("traffic-cone", -2.8, 6, 0, 0.55);
  // --- A site (west): container yard ---
  addProp("shipping-container", -42, 20, 0, 6.0, { w: 6.0, d: 3.0, h: 2.9 });
  addProp("shipping-container", -38, -22, Math.PI / 2, 6.0, { w: 3.0, d: 6.0, h: 2.9 });
  addProp("shipping-container-structure", -28, 14, 0.3, 6.5, { w: 6.0, d: 3.4, h: 2.9 });
  addProp("tires", -40, -12, 0, 1.9, { w: 1.9, d: 1.4, h: 1.1 });
  addProp("sack-trench", -26, -14, 0, 4.2, { w: 4.2, d: 1.6, h: 1.0 });
  addProp("exploding-barrel", -31, 4, 0, 1.0, { w: 1.0, d: 1.0, h: 1.3 });
  addProp("pallet", -36, 30, 0.2, 3.4);
  // --- B site (east): industrial corner ---
  addProp("shipping-container", 42, -18, 0, 6.0, { w: 6.0, d: 3.0, h: 2.9 });
  addProp("dumpster", 40, 14, 0, 2.7, { w: 2.7, d: 2.3, h: 1.4 });
  addProp("water-tank", 33, -34, 0, 2.4, { w: 2.4, d: 1.6, h: 3.4 });
  addProp("sack-trench-small", 26, -8, Math.PI / 2, 2.6, { w: 1.4, d: 2.6, h: 1.0 });
  addProp("metal-fence", 30, 22, 0, 3.2, { w: 3.2, d: 0.3, h: 1.8 });
  addProp("gas-tank", 44, 2, 0, 1.4, { w: 1.4, d: 1.4, h: 1.8 });
  addProp("wood-planks", 22, 30, 0.4, 2.2);
  // --- spawn yards: clutter, no colliders needed for tiny bits ---
  addProp("cardboard-boxes", -8, 41, 0.3, 1.6, { w: 1.6, d: 1.4, h: 1.2 });
  addProp("pipes", 9, -42, 0, 2.4, { w: 2.4, d: 1.2, h: 0.9 });
  addProp("brick-wall", -16, -38, 0.15, 2.8, { w: 2.8, d: 0.5, h: 1.6 });
  } else {
  // --- COMPOUND props: containers wall the outer corridors, dense site cover ---
  addProp("shipping-container", -44, -26, Math.PI / 2, 6.0, { w: 3.0, d: 6.0, h: 2.9 });
  addProp("shipping-container", 44, -26, Math.PI / 2, 6.0, { w: 3.0, d: 6.0, h: 2.9 });
  addProp("shipping-container-structure", -38, 14, 0.2, 6.5, { w: 6.0, d: 3.4, h: 2.9 });
  addProp("shipping-container-structure", 38, 14, -0.2, 6.5, { w: 6.0, d: 3.4, h: 2.9 });
  addProp("dumpster", -18, -36, 0, 2.7, { w: 2.7, d: 2.3, h: 1.4 });
  addProp("dumpster", 18, -36, 0, 2.7, { w: 2.7, d: 2.3, h: 1.4 });
  addProp("water-tank", -36, -46, 0, 2.4, { w: 2.4, d: 1.6, h: 3.4 });
  addProp("gas-tank", 36, -46, 0, 1.4, { w: 1.4, d: 1.4, h: 1.8 });
  addProp("sack-trench", 0, -42, 0, 4.2, { w: 4.2, d: 1.6, h: 1.0 });
  addProp("tires", -41, 30, 0, 1.9, { w: 1.9, d: 1.4, h: 1.1 });
  addProp("tires", 41, 30, 0, 1.9, { w: 1.9, d: 1.4, h: 1.1 });
  addProp("barrier-large", -7, 18, 0.3, 3.6, { w: 3.6, d: 0.8, h: 2.2 });
  addProp("barrier-large", 7, 18, -0.3, 3.6, { w: 3.6, d: 0.8, h: 2.2 });
  addProp("exploding-barrel", -24, 6, 0, 1.0, { w: 1.0, d: 1.0, h: 1.3 });
  addProp("exploding-barrel", 24, 6, 0, 1.0, { w: 1.0, d: 1.0, h: 1.3 });
  addProp("cardboard-boxes", -8, 41, 0.3, 1.6, { w: 1.6, d: 1.4, h: 1.2 });
  addProp("pipes", 9, 42, 0, 2.4, { w: 2.4, d: 1.2, h: 0.9 });
  addProp("traffic-cone", -2, 22, 0, 0.55);
  addProp("traffic-cone", 3, -16, 0, 0.55);
  addProp("brick-wall", 0, -25, 0.1, 2.8, { w: 2.8, d: 0.5, h: 1.6 });
  }

  // ============ site graffiti (sprayed A/B markers) ============
  function graffitiTex(letter: string, rgb: string): THREE.CanvasTexture {
    const cv = document.createElement("canvas"); cv.width = 256; cv.height = 256;
    const c = cv.getContext("2d")!;
    c.clearRect(0, 0, 256, 256);
    // spray haze backdrop
    c.fillStyle = "rgba(" + rgb + ",0.12)";
    for (let i = 0; i < 40; i++) {
      const a = Math.random() * Math.PI * 2, r = 60 + Math.random() * 60;
      c.beginPath(); c.arc(128 + Math.cos(a) * r * 0.5, 128 + Math.sin(a) * r * 0.5, 14 + Math.random() * 22, 0, 6.29); c.fill();
    }
    // dashed stencil ring
    c.strokeStyle = "rgba(" + rgb + ",0.85)"; c.lineWidth = 7; c.setLineDash([22, 13]);
    c.beginPath(); c.arc(128, 128, 104, 0, 6.29); c.stroke();
    c.setLineDash([]);
    // letter with rough double-pass for spray feel
    c.font = "900 150px Rajdhani, Arial, sans-serif"; c.textAlign = "center"; c.textBaseline = "middle";
    c.fillStyle = "rgba(" + rgb + ",0.32)"; c.fillText(letter, 131, 141);
    c.fillStyle = "rgba(" + rgb + ",0.95)"; c.fillText(letter, 128, 138);
    // drips
    c.fillStyle = "rgba(" + rgb + ",0.55)";
    for (let i = 0; i < 7; i++) {
      const dx2 = 70 + Math.random() * 116;
      c.fillRect(dx2, 180 + Math.random() * 16, 3, 12 + Math.random() * 26);
    }
    const t = new THREE.CanvasTexture(cv);
    t.colorSpace = THREE.SRGBColorSpace; t.anisotropy = 4;
    return t;
  }
  function addSiteMarkers(letter: "A" | "B") {
    const st = SITES[letter];
    const rgb = letter === "A" ? "255,196,60" : "120,175,255";
    const tex = graffitiTex(letter, rgb);
    const mat = new THREE.MeshBasicMaterial({ map: tex, transparent: true, depthWrite: false, polygonOffset: true, polygonOffsetFactor: -2 });
    // ground stencil
    const gp = new THREE.Mesh(new THREE.PlaneGeometry(8, 8), mat);
    gp.rotation.x = -Math.PI / 2; gp.rotation.z = rand(-0.3, 0.3);
    gp.position.set(st.x, 0.06, st.z);
    gp.renderOrder = 1;
    scene.add(gp);
    if (MAP === "bazaar") {
      // wall sign on the outer perimeter (faces the site)
      const wp = new THREE.Mesh(new THREE.PlaneGeometry(5.5, 5.5), mat);
      wp.position.set(letter === "A" ? -50.9 : 50.9, 3.1, st.z);
      wp.rotation.y = letter === "A" ? Math.PI / 2 : -Math.PI / 2;
      scene.add(wp);
      // second sign on the inner long wall (visible from mid)
      const ip = new THREE.Mesh(new THREE.PlaneGeometry(3.6, 3.6), mat);
      ip.position.set(letter === "A" ? -20.65 : 20.65, 2.3, st.z);
      ip.rotation.y = letter === "A" ? -Math.PI / 2 : Math.PI / 2;
      scene.add(ip);
    } else {
      // compound: sign on the north perimeter wall above each site, facing south
      const wp = new THREE.Mesh(new THREE.PlaneGeometry(5.5, 5.5), mat);
      wp.position.set(st.x, 3.4, -50.9);
      scene.add(wp);
      // second sign on the site divider wall z=-32, facing the approach
      const ip = new THREE.Mesh(new THREE.PlaneGeometry(3.6, 3.6), mat);
      ip.position.set(st.x, 2.3, -31.35);
      scene.add(ip);
    }
  }
  addSiteMarkers("A");
  addSiteMarkers("B");

  // ============ skinned character visuals (mixer-driven, weapon-in-hand sync) ============
  const MODEL_YAW = 0; // GLB forward offset vs engine yaw (atan2(dx,dz) → +Z forward)
  interface CharVisual {
    root: THREE.Group; mixer: THREE.AnimationMixer | null;
    actions: Record<string, THREE.AnimationAction>;
    cur: string; hitT: number; dead: boolean; ready: boolean;
    headProxy: THREE.Mesh; proxies: THREE.Mesh[];
    weaponNodes: Record<string, THREE.Object3D | undefined>;
    weapon: WeaponId;
    tryAttach: () => boolean;
  }
  function findBone(rootO: THREE.Object3D, name: string): THREE.Object3D | null {
    let r: THREE.Object3D | null = null;
    rootO.traverse((o) => { if (!r && (o as THREE.Bone).isBone && o.name === name) r = o; });
    return r;
  }
  function buildCharVisual(kind: "enemy" | "friend", tint?: number): CharVisual {
    const root = new THREE.Group();
    // sized from the GLB's per-bone vertex bounds (head is a big toon head ~0.42 cube)
    const headProxy = new THREE.Mesh(new THREE.BoxGeometry(0.44, 0.46, 0.46), proxyMat);
    const chestProxy = new THREE.Mesh(new THREE.BoxGeometry(0.52, 0.6, 0.42), proxyMat);
    const legProxy = new THREE.Mesh(new THREE.BoxGeometry(0.5, 0.9, 0.38), proxyMat);
    headProxy.visible = chestProxy.visible = legProxy.visible = false;
    const v: CharVisual = {
      root, mixer: null, actions: {}, cur: "", hitT: 0, dead: false, ready: false,
      headProxy, proxies: [chestProxy, legProxy], weaponNodes: {}, weapon: "rifle",
      tryAttach: () => false,
    };
    const wsV = new THREE.Vector3();
    v.tryAttach = () => {
      if (v.ready) return true;
      const lib = charLib[kind];
      if (!lib) return false;
      const model = SkeletonUtils.clone(lib.scene);
      model.scale.setScalar(lib.scale);
      model.position.y = lib.yOff;
      model.rotation.y = MODEL_YAW;
      model.traverse((o) => {
        const mm = o as THREE.Mesh;
        if (mm.isMesh || (o as THREE.SkinnedMesh).isSkinnedMesh) {
          mm.castShadow = true;
          mm.frustumCulled = false;
          if (tint !== undefined) {
            const src = mm.material as THREE.MeshStandardMaterial;
            if (src && src.color) {
              mm.material = src.clone();
              (mm.material as THREE.MeshStandardMaterial).color.lerp(new THREE.Color(tint), 0.45);
            }
          }
        }
      });
      root.add(model);
      root.updateMatrixWorld(true);
      v.mixer = new THREE.AnimationMixer(model);
      lib.clips.forEach((clip, nm) => { v.actions[nm] = v.mixer!.clipAction(clip); });
      // weapon meshes live in the right hand (Index1.R) — hide all, then show current
      for (const wn of ALL_WEAPON_NODES) {
        const node = model.getObjectByName(wn);
        if (node) { node.visible = false; v.weaponNodes[wn] = node; }
      }
      // bone-following invisible hit proxies (head priority + body + legs)
      const headBone = findBone(model, "Head");
      const chestBone = findBone(model, "Abdomen") || findBone(model, "Torso");
      const hipBone = findBone(model, "Hips");
      const attachProxy = (bone: THREE.Object3D | null, proxy: THREE.Mesh, upOff: number) => {
        if (!bone) return;
        bone.getWorldScale(wsV);
        proxy.scale.set(1 / Math.max(wsV.x, 1e-6), 1 / Math.max(wsV.y, 1e-6), 1 / Math.max(wsV.z, 1e-6));
        proxy.position.set(0, upOff / Math.max(wsV.y, 1e-6), 0);
        bone.add(proxy);
      };
      attachProxy(headBone, v.headProxy, 0.16);
      attachProxy(chestBone, v.proxies[0], 0.1);
      attachProxy(hipBone, v.proxies[1], -0.45);
      // arm proxies track the upper-arm bones so flank shots register
      const armL = findBone(model, "UpperArm.L");
      const armR = findBone(model, "UpperArm.R");
      const mkArmProxy = () => {
        const pr = new THREE.Mesh(new THREE.BoxGeometry(0.16, 0.5, 0.2), proxyMat);
        pr.visible = false;
        v.proxies.push(pr);
        return pr;
      };
      if (armL) attachProxy(armL, mkArmProxy(), -0.18);
      if (armR) attachProxy(armR, mkArmProxy(), -0.18);
      v.ready = true;
      applyCharWeapon(v);
      if (v.dead) { setAnim(v, "Death", 0.01, true); const a = v.actions["Death"]; if (a) { a.time = a.getClip().duration; } }
      else setAnim(v, "Idle", 0.01);
      return true;
    };
    v.tryAttach();
    return v;
  }
  function applyCharWeapon(v: CharVisual) {
    if (!v.ready) return;
    for (const wn of ALL_WEAPON_NODES) { const n = v.weaponNodes[wn]; if (n) n.visible = false; }
    const node = v.weaponNodes[WEAPON_NODE[v.weapon]];
    if (node) node.visible = true;
  }
  function setCharWeapon(v: CharVisual, w: WeaponId) {
    if (v.weapon === w) return;
    v.weapon = w;
    applyCharWeapon(v);
  }
  function setAnim(v: CharVisual, name: string, fade = 0.18, once = false) {
    if (!v.ready || v.cur === name) return;
    const next = v.actions[name];
    if (!next) return;
    const prev = v.actions[v.cur];
    if (prev) prev.fadeOut(fade);
    next.reset().fadeIn(fade);
    if (once) { next.setLoop(THREE.LoopOnce, 1); next.clampWhenFinished = true; }
    else { next.setLoop(THREE.LoopRepeat, Infinity); next.clampWhenFinished = false; }
    next.play();
    v.cur = name;
  }
  function charDie(v: CharVisual) {
    v.dead = true; v.hitT = 0;
    if (v.ready) setAnim(v, "Death", 0.1, true);
  }
  function charRevive(v: CharVisual) {
    v.dead = false; v.hitT = 0;
    if (v.ready) setAnim(v, "Idle", 0.05);
  }
  // single state machine: movement + combat drive the clip, weapon rides the hand bone
  function animChar(v: CharVisual, dt: number, speed: number, shooting: boolean, aiming: boolean) {
    if (!v.ready) { if (!v.tryAttach()) return; }
    if (v.hitT > 0) v.hitT -= dt;
    if (v.dead) { setAnim(v, "Death", 0.1, true); v.mixer!.update(dt); return; }
    const moving = speed > 0.6;
    const fast = speed > 3.2;
    let want: string;
    if (moving) {
      if (shooting && v.actions["Run_Shoot"]) want = "Run_Shoot";
      else if (!fast && shooting && v.actions["Walk_Shoot"]) want = "Walk_Shoot";
      else if (!fast && v.actions["Walk"]) want = "Walk";
      else want = v.actions["Run_Gun"] ? "Run_Gun" : "Run";
    } else if (v.hitT > 0 && v.actions["HitReact"]) {
      want = "HitReact";
    } else {
      want = (shooting || aiming) && v.actions["Idle_Shoot"] ? "Idle_Shoot" : "Idle";
    }
    setAnim(v, want);
    const a = v.actions[v.cur];
    if (a) a.timeScale = moving ? clamp(speed / 5, 0.75, 1.7) : 1;
    v.mixer!.update(dt);
  }

  // ============ bots ============
  interface BotTarget { pos: THREE.Vector3; eyeY: number; kind: "player" | "peer" | "bot"; peer?: Peer; bot?: Bot }
  interface Bot {
    netId: string; respawnT: number; stepAcc: number;
    ntx: number; ntz: number; ntyaw: number; nmoving: boolean; naiming: boolean; nseen: number;
    g: THREE.Group; vis: CharVisual; head: THREE.Mesh; body: THREE.Mesh; gun: THREE.Group; hittable: THREE.Mesh[]; shootT: number;
    name: string; hp: number; alive: boolean;
    path: number[]; pathI: number; repathT: number;
    seeT: number; fireT: number; burstLeft: number; burstCd: number;
    strafeDir: number; strafeT: number; deadT: number; flinchT: number; deadDir: number;
    yaw: number; lastShotFrom: number;
    team: "blue" | "red"; kills: number; deaths: number;
    role: "A" | "mid" | "B"; reactT: number; wasSee: boolean; nadeCd: number;
    coverT: number; coverCd: number; holdT: number; holdYaw: number;
    gx: number; gz: number; hasGoal: boolean;
    stuckT: number; lastPX: number; lastPZ: number; sideT: number; sideDir: number;
    plantT: number; defuseT: number; carrier: boolean; defSite: "A" | "B";
  }
  const bots: Bot[] = [];
  const ENEMY_COUNT = mp ? 3 : 4;
  const FRIEND_COUNT = mp ? 0 : 3;
  const BLUE_NAMES = ["Dash", "Echo", "Nova", "Flint", "Onyx"];
  const skinMat = new THREE.MeshStandardMaterial({ color: 0xc79b6f, roughness: 0.8 });
  // painted face on the front of the head box; plain skin on other 5 faces
  const faceMat = new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.85 });
  texLoader.load("/tex/face.jpg", (t) => { t.colorSpace = THREE.SRGBColorSpace; faceMat.map = t; faceMat.needsUpdate = true; });
  const headMats: THREE.Material[] = [skinMat, skinMat, skinMat, skinMat, faceMat, skinMat];
  const enemyBodyMat = new THREE.MeshStandardMaterial({ color: 0xbfc7d4, roughness: 0.8 });
  const enemyVestMat = new THREE.MeshStandardMaterial({ color: 0x4a4422, roughness: 0.9 });
  const enemyPantsMat = new THREE.MeshStandardMaterial({ color: 0x9aa3b2, roughness: 0.85 });
  loadTex("/tex/camo.jpg", 1.6, 1.6, [enemyBodyMat]);
  loadTex("/tex/camo.jpg", 2.2, 2.2, [enemyPantsMat]);
  loadTex("/tex/vest.jpg", 1.2, 0.9, [enemyVestMat]);
  enemyVestMat.color.set(0xffffff);
  const gunMatDark = new THREE.MeshStandardMaterial({ color: 0x2c2c2e, roughness: 0.5, metalness: 0.4 });

  interface SoldierRig {
    head: THREE.Mesh; chest: THREE.Mesh; torso: THREE.Group;
    hipL: THREE.Group; hipR: THREE.Group; shoulderL: THREE.Group; shoulderR: THREE.Group;
    gun: THREE.Group; gait: number; lean: number; aimAmt: number;
  }
  const outlineMat = new THREE.MeshBasicMaterial({ color: 0x14100a, side: THREE.BackSide });
  function addOutline(m: THREE.Mesh, scale = 1.05) {
    const o = new THREE.Mesh(m.geometry, outlineMat);
    o.scale.setScalar(scale);
    m.add(o);
  }
  function soldierBody(g: THREE.Group, bodyM: THREE.Material, vestM: THREE.Material, pantsM: THREE.Material): SoldierRig {
    const bootMat = new THREE.MeshStandardMaterial({ color: 0x191713, roughness: 1 });
    const blackMat = new THREE.MeshStandardMaterial({ color: 0x1f1d18, roughness: 0.9 });

    // ----- torso group (pivot at hip line y=0.94) so it can bob/lean as a unit -----
    const torso = new THREE.Group();
    torso.position.y = 0.94;
    const chest = new THREE.Mesh(new THREE.BoxGeometry(0.5, 0.6, 0.28), bodyM);
    chest.position.y = 0.26; chest.castShadow = true;
    addOutline(chest, 1.045);
    const vest = new THREE.Mesh(new THREE.BoxGeometry(0.56, 0.44, 0.36), vestM);
    vest.position.y = 0.3;
    for (let i = 0; i < 3; i++) {
      const pouch = new THREE.Mesh(new THREE.BoxGeometry(0.11, 0.13, 0.06), pantsM);
      pouch.position.set(-0.16 + i * 0.16, 0.18, 0.2);
      torso.add(pouch);
    }
    const belt = new THREE.Mesh(new THREE.BoxGeometry(0.52, 0.07, 0.3), blackMat);
    belt.position.y = -0.02;
    const head = new THREE.Mesh(new THREE.BoxGeometry(0.24, 0.26, 0.24), headMats);
    head.position.y = 0.72; head.castShadow = true;
    addOutline(head, 1.07);
    const mask = new THREE.Mesh(new THREE.BoxGeometry(0.25, 0.12, 0.25), new THREE.MeshStandardMaterial({ color: 0x23211d, roughness: 1 }));
    mask.position.y = 0.66;
    const helmet = new THREE.Mesh(new THREE.SphereGeometry(0.16, 10, 6, 0, Math.PI * 2, 0, Math.PI / 2), vestM);
    helmet.position.y = 0.8; helmet.scale.set(1, 0.85, 1.1);
    const brim = new THREE.Mesh(new THREE.BoxGeometry(0.3, 0.025, 0.32), vestM);
    brim.position.y = 0.805;
    const gog = new THREE.Mesh(new THREE.BoxGeometry(0.2, 0.05, 0.04), new THREE.MeshStandardMaterial({ color: 0x141a20, roughness: 0.3, metalness: 0.4 }));
    gog.position.set(0, 0.76, 0.13);
    torso.add(chest, vest, belt, head, mask, helmet, brim, gog);

    // ----- legs: pivot group at the HIP, geometry hangs below -----
    function makeLeg(x: number): THREE.Group {
      const hip = new THREE.Group();
      hip.position.set(x, 0.94, 0);
      const thigh = new THREE.Mesh(new THREE.BoxGeometry(0.19, 0.5, 0.21), pantsM);
      thigh.position.y = -0.25; thigh.castShadow = true;
      addOutline(thigh, 1.06);
      const shin = new THREE.Mesh(new THREE.BoxGeometry(0.165, 0.4, 0.18), pantsM);
      shin.position.y = -0.66; shin.castShadow = true;
      addOutline(shin, 1.06);
      const boot = new THREE.Mesh(new THREE.BoxGeometry(0.19, 0.12, 0.28), bootMat);
      boot.position.set(0, -0.88, 0.03);
      const kneepad = new THREE.Mesh(new THREE.BoxGeometry(0.2, 0.14, 0.1), vestM);
      kneepad.position.set(0, -0.48, 0.08);
      hip.add(thigh, shin, boot, kneepad);
      return hip;
    }
    const hipL = makeLeg(-0.14), hipR = makeLeg(0.14);

    // ----- arms: pivot group at the SHOULDER (attached to torso) -----
    function makeArm(x: number): THREE.Group {
      const shoulder = new THREE.Group();
      shoulder.position.set(x, 0.5, 0);
      const upper = new THREE.Mesh(new THREE.BoxGeometry(0.13, 0.28, 0.15), bodyM);
      upper.position.y = -0.14;
      addOutline(upper, 1.07);
      const fore = new THREE.Mesh(new THREE.BoxGeometry(0.115, 0.26, 0.13), bodyM);
      fore.position.y = -0.4;
      const glove = new THREE.Mesh(new THREE.BoxGeometry(0.1, 0.1, 0.12), blackMat);
      glove.position.y = -0.56;
      const pad = new THREE.Mesh(new THREE.BoxGeometry(0.15, 0.12, 0.17), vestM);
      pad.position.y = -0.28;
      shoulder.add(upper, fore, glove, pad);
      return shoulder;
    }
    const shoulderL = makeArm(-0.33), shoulderR = makeArm(0.33);
    torso.add(shoulderL, shoulderR);

    // ----- rifle held two-handed, parented to torso -----
    const gun = new THREE.Group();
    const recv = new THREE.Mesh(new THREE.BoxGeometry(0.05, 0.07, 0.34), gunMatDark); recv.position.z = -0.1;
    const brl = new THREE.Mesh(new THREE.CylinderGeometry(0.013, 0.013, 0.3, 8), gunMatDark);
    brl.rotation.x = Math.PI / 2; brl.position.z = -0.4;
    const gmag = new THREE.Mesh(new THREE.BoxGeometry(0.035, 0.15, 0.06), new THREE.MeshStandardMaterial({ color: 0x3d3322, roughness: 0.8 }));
    gmag.position.set(0, -0.1, -0.12); gmag.rotation.x = 0.4;
    const gstock = new THREE.Mesh(new THREE.BoxGeometry(0.04, 0.05, 0.16), new THREE.MeshStandardMaterial({ color: 0x59401f, roughness: 0.9 }));
    gstock.position.z = 0.12;
    gun.add(recv, brl, gmag, gstock);
    gun.position.set(0.16, 0.34, -0.28);
    gun.rotation.y = -0.08;
    torso.add(gun);

    g.add(torso, hipL, hipR);
    return { head, chest, torso, hipL, hipR, shoulderL, shoulderR, gun, gait: 0, lean: 0, aimAmt: 0 };
  }
  // gait cycle: drive limbs from actual horizontal speed; framerate independent
  function animateSoldier(rig: SoldierRig, dt: number, speed: number, strafeLean: number, aiming = false) {
    rig.aimAmt = damp(rig.aimAmt, aiming ? 1 : 0, 10, dt);
    const moving = speed > 0.4;
    const speedN = clamp(speed / 4.8, 0, 1.4);
    if (moving) rig.gait += dt * (5.5 + speedN * 5.5);
    const swing = moving ? Math.sin(rig.gait) * (0.45 + speedN * 0.35) : 0;
    const k = 1 - Math.exp(-14 * dt);
    rig.hipL.rotation.x += (swing - rig.hipL.rotation.x) * k;
    rig.hipR.rotation.x += (-swing - rig.hipR.rotation.x) * k;
    // arms counter-swing, right arm stays near the gun
    const aimRaise = rig.aimAmt;
    rig.shoulderL.rotation.x += (((-swing * 0.7 * (1 - aimRaise)) - 0.35 - aimRaise * 0.85) - rig.shoulderL.rotation.x) * k;
    rig.shoulderR.rotation.x += (((swing * 0.25 * (1 - aimRaise)) - 0.55 - aimRaise * 0.7) - rig.shoulderR.rotation.x) * k;
    rig.gun.position.y = 0.34 + aimRaise * 0.14;
    rig.gun.position.z = -0.28 - aimRaise * 0.06;
    // torso: bob + forward lean with speed + strafe lean, breathing at idle
    const bobY = moving ? Math.abs(Math.sin(rig.gait)) * 0.045 * speedN : Math.sin(performance.now() * 0.0012) * 0.008;
    rig.torso.position.y += ((0.94 + bobY) - rig.torso.position.y) * k;
    rig.torso.rotation.x += ((moving ? 0.1 * speedN : 0) - rig.torso.rotation.x) * k;
    rig.lean += (strafeLean - rig.lean) * k;
    rig.torso.rotation.z = rig.lean;
  }
  function buildBot(name: string, team: "blue" | "red" = "red"): Bot {
    const g = new THREE.Group();
    const vis = buildCharVisual(team === "blue" ? "friend" : "enemy", team === "blue" ? 0x3f6aa8 : undefined);
    g.add(vis.root);
    if (team === "blue") g.add(makeNameSprite(name));
    g.visible = false;
    scene.add(g);
    return {
      team, kills: 0, deaths: 0,
      netId: "bot-" + name.toLowerCase(), respawnT: 0, stepAcc: 0,
      ntx: 0, ntz: 0, ntyaw: 0, nmoving: false, naiming: false, nseen: 0,
      g, vis, head: vis.headProxy, body: vis.proxies[0], gun: new THREE.Group(), hittable: vis.proxies, name, hp: 100, alive: false, shootT: 0,
      path: [], pathI: 0, repathT: 0, seeT: 0, fireT: rand(0.4, 1.2),
      burstLeft: 0, burstCd: 0, strafeDir: 1, strafeT: 0, deadT: 0, flinchT: 0, deadDir: 1, yaw: 0, lastShotFrom: 0,
      role: "mid", reactT: 0, wasSee: false, nadeCd: rand(6, 14),
      coverT: 0, coverCd: 0, holdT: 0, holdYaw: 0,
      gx: 0, gz: 0, hasGoal: false,
      stuckT: 0, lastPX: 0, lastPZ: 0, sideT: 0, sideDir: 1,
      plantT: 0, defuseT: 0, carrier: false, defSite: "A",
    };
  }
  for (let i = 0; i < ENEMY_COUNT; i++) bots.push(buildBot(BOT_NAMES[i % BOT_NAMES.length], "red"));
  for (let i = 0; i < FRIEND_COUNT; i++) bots.push(buildBot(BLUE_NAMES[i % BLUE_NAMES.length], "blue"));

  const ENEMY_SPAWNS: [number, number][] = [[-4, -44], [4, -44], [-12, -45], [12, -45], [0, -46]];
  const PLAYER_SPAWN: [number, number] = [0, 44];
  const FRIEND_SPAWNS: [number, number][] = [[-4, 44], [4, 44], [-9, 45], [9, 45], [0, 46]];
  const isBlueName = (n: string) => n === "YOU" || n === myName || bots.some((b) => b.team === "blue" && b.name === n);

  // ============ multiplayer (BroadcastChannel, same-browser tabs) ============
  const mpColors = [0x3f6aa8, 0x9a4ea8, 0x3f8f6e, 0xa8743f, 0x5a5aa8, 0xa83f55];
  interface Peer {
    ready?: boolean;
    id: string; name: string; g: THREE.Group; vis: CharVisual; head: THREE.Mesh; parts: THREE.Mesh[];
    kills: number; deaths: number; alive: boolean; vx: number; vz: number;
    tx: number; ty: number; tz: number; tyaw: number; moving: boolean; lastSeen: number; deadT: number; lastShotT: number;
  }
  const peers = new Map<string, Peer>();
  function makeNameSprite(name: string): THREE.Sprite {
    const cv = document.createElement("canvas"); cv.width = 256; cv.height = 64;
    const c = cv.getContext("2d")!;
    c.fillStyle = "rgba(0,0,0,0.45)"; c.fillRect(28, 8, 200, 48);
    c.font = "bold 34px Rajdhani, sans-serif"; c.textAlign = "center";
    c.fillStyle = "#9fd2ff"; c.fillText(name, 128, 43);
    const t = new THREE.CanvasTexture(cv); t.colorSpace = THREE.SRGBColorSpace;
    const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: t, transparent: true, depthWrite: false }));
    s.scale.set(1.7, 0.42, 1); s.position.y = 2.18;
    return s;
  }
  function buildAvatar(id: string, name: string): Peer {
    let hash = 0;
    for (let i = 0; i < id.length; i++) hash = (hash * 31 + id.charCodeAt(i)) | 0;
    const tint = mpColors[Math.abs(hash) % mpColors.length];
    const g = new THREE.Group();
    const vis = buildCharVisual("friend", tint);
    // peer yaw is camera-convention (0 = -Z); the GLB faces +Z at rotation 0 — flip the model half a turn
    vis.root.rotation.y = Math.PI;
    g.add(vis.root);
    g.add(makeNameSprite(name));
    scene.add(g);
    return { id, name, g, vis, head: vis.headProxy, parts: vis.proxies, kills: 0, deaths: 0, alive: true, vx: 0, vz: 0, tx: 0, ty: 0, tz: 0, tyaw: 0, moving: false, lastSeen: performance.now(), deadT: 0, lastShotT: 0 };
  }
  // --- WebRTC transport (PeerJS star topology: first joiner hosts, others connect) ---
  let peer: RTCPeer | null = null;
  let isHost = false;
  let netDead = false;
  const conns = new Map<string, DataConnection>();
  const RTC_CONFIG = {
    iceServers: [
      { urls: "stun:stun.l.google.com:19302" },
      { urls: "stun:global.stun.twilio.com:3478" },
      { urls: "turn:openrelay.metered.ca:80", username: "openrelayproject", credential: "openrelayproject" },
      { urls: "turn:openrelay.metered.ca:443", username: "openrelayproject", credential: "openrelayproject" },
      { urls: "turn:openrelay.metered.ca:443?transport=tcp", username: "openrelayproject", credential: "openrelayproject" },
    ],
  };
  const PEER_OPTS = { debug: 0, config: RTC_CONFIG };
  const HOST_ID = "strike-protocol-" + room.toLowerCase();
  function bcSend(msg: Record<string, unknown>) {
    const m = { ...msg, id: myId };
    conns.forEach((c) => { if (c.open) { try { c.send(m); } catch { /* conn died */ } } });
  }
  function handleNet(m: Record<string, unknown>, via: DataConnection | null) {
    if (!m || typeof m.id !== "string" || m.id === myId) return;
    if (isHost && via) {
      conns.forEach((c) => { if (c !== via && c.open) { try { c.send(m); } catch { /* conn died */ } } });
    }
    handleMsg(m);
  }
  function wireConn(c: DataConnection) {
    c.on("data", (d) => handleNet(d as Record<string, unknown>, c));
    c.on("open", () => { conns.set(c.connectionId, c); sendState(); });
    c.on("close", () => { conns.delete(c.connectionId); });
    c.on("error", () => { conns.delete(c.connectionId); });
  }
  function becomeHost() {
    if (netDead) return;
    const p = new RTCPeer(HOST_ID, PEER_OPTS);
    peer = p;
    p.on("open", () => {
      isHost = true;
      centerMsg("ROOM " + room + " — HOSTING · SHARE THE LINK", 3, "#7fb3ff");
    });
    p.on("connection", (c) => wireConn(c));
    p.on("error", (err: Error & { type?: string }) => {
      if (err.type === "unavailable-id") { p.destroy(); joinAsClient(); }
      else if (err.type === "network" || err.type === "server-error" || err.type === "socket-error") {
        centerMsg("CONNECTION LOST — RETRYING", 2, "#ff9d7a");
        setTimeout(() => { if (!netDead) { p.destroy(); becomeHost(); } }, 2500);
      }
    });
    p.on("disconnected", () => { if (!netDead) p.reconnect(); });
  }
  function joinAsClient() {
    if (netDead) return;
    const p = new RTCPeer(PEER_OPTS);
    peer = p;
    isHost = false;
    p.on("open", () => {
      const c = p.connect(HOST_ID, { reliable: false });
      wireConn(c);
      c.on("open", () => centerMsg("ROOM " + room + " — CONNECTED", 2.5, "#9fe870"));
      c.on("close", () => {
        peers.forEach((_, id) => removePeer(id));
        centerMsg("HOST LEFT — RECONNECTING", 2.5, "#ff9d7a");
        setTimeout(() => { if (!netDead) { p.destroy(); becomeHost(); } }, rand(400, 2200));
      });
    });
    p.on("error", (err: Error & { type?: string }) => {
      if (err.type === "peer-unavailable") {
        setTimeout(() => { if (!netDead) { p.destroy(); becomeHost(); } }, rand(100, 600));
      } else if (err.type === "network" || err.type === "server-error" || err.type === "socket-error") {
        setTimeout(() => { if (!netDead) { p.destroy(); becomeHost(); } }, 2500);
      }
    });
    p.on("disconnected", () => { if (!netDead) p.reconnect(); });
  }
  function removePeer(id: string) {
    const p = peers.get(id);
    if (!p) return;
    scene.remove(p.g);
    peers.delete(id);
  }
  let respawnT = 0, stateAcc = 0, lastHitAcceptT = 0;
  function sendState() {
    bcSend({ t: "s", x: P.pos.x, y: P.pos.y, z: P.pos.z, yaw: P.yaw, n: myName, a: P.alive, k: P.kills, d: P.deaths, mv: Math.hypot(P.vel.x, P.vel.z) > 0.8, w: cur });
  }
  const MP_SPAWNS: [number, number][] = [[0, 44], [-34, 38], [34, 38], [-4, -44], [4, -44], [-36, -40], [36, -40], [-34, 8], [34, 8], [0, 28], [-30, -26], [30, -26], [-12, 16], [12, -2], [0, -16]];
  function randomSpawn(): [number, number] {
    let best: [number, number] = MP_SPAWNS[0], bd = -1;
    for (const sp of MP_SPAWNS) {
      let nearest = 1e9;
      if (P.alive) nearest = Math.min(nearest, (sp[0] - P.pos.x) ** 2 + (sp[1] - P.pos.z) ** 2);
      peers.forEach((p) => { if (p.alive) nearest = Math.min(nearest, (sp[0] - p.g.position.x) ** 2 + (sp[1] - p.g.position.z) ** 2); });
      for (const b of bots) if (b.alive) nearest = Math.min(nearest, (sp[0] - b.g.position.x) ** 2 + (sp[1] - b.g.position.z) ** 2);
      if (nearest > bd) { bd = nearest; best = sp; }
    }
    return [best[0] + rand(-1.5, 1.5), best[1] + rand(-1.5, 1.5)];
  }
  function mpRespawn() {
    const sp = randomSpawn();
    P.pos.set(sp[0], 0, sp[1]); P.vel.set(0, 0, 0);
    P.yaw = Math.atan2(sp[0], sp[1]); P.pitch = 0; // face map center
    P.hp = 100; P.alive = true;
    frags = 1; smokes = 1;
    (Object.keys(WEAPONS) as WeaponId[]).forEach((w) => {
      if (owned[w] && !WEAPONS[w].melee) ammo[w] = { mag: WEAPONS[w].mag, res: WEAPONS[w].reserve };
    });
    reloadT = 0;
    elRespawn.style.display = "none";
    elXhair.style.display = "block";
    vmRoot.visible = true; deathCamT = 0; specIdx = -1;
    elDeathFade.style.opacity = "0";
    sendState();
  }
  function handleMsg(m: Record<string, unknown>) {
      const mid = m.id as string;
      if (m.t === "s") {
        let p = peers.get(mid);
        if (!p) {
          p = buildAvatar(mid, String(m.n || "PEER").slice(0, 12).toUpperCase());
          p.tx = Number(m.x) || 0; p.ty = Number(m.y) || 0; p.tz = Number(m.z) || 0;
          p.g.position.set(p.tx, p.ty, p.tz);
          peers.set(mid, p);
          centerMsg(p.name + " JOINED", 1.6, "#7fb3ff");
          sendState();
          if (isHost && !warmup) bcSend({ t: "begin", lim: dmLimit }); // late joiner skips lobby
        }
        p.tx = Number(m.x) || 0; p.ty = Number(m.y) || 0; p.tz = Number(m.z) || 0;
        p.tyaw = Number(m.yaw) || 0; p.moving = !!m.mv;
        p.kills = Number(m.k) || 0; p.deaths = Number(m.d) || 0;
        if (typeof m.w === "string" && m.w in WEAPONS) setCharWeapon(p.vis, m.w as WeaponId);
        if (p.alive && !m.a) { p.deadT = 0; charDie(p.vis); }
        if (!p.alive && !!m.a) { p.g.visible = true; charRevive(p.vis); }
        p.alive = !!m.a;
        p.lastSeen = performance.now();
      } else if (m.t === "shot") {
        { const sp2 = peers.get(mid); if (sp2) sp2.lastShotT = performance.now(); }
        const from = new THREE.Vector3(Number(m.fx), Number(m.fy), Number(m.fz));
        addTracer(from, new THREE.Vector3(Number(m.ex), Number(m.ey), Number(m.ez)), 0xffb38a, 0.08);
        const pv = panVol(from);
        sShot((m.w as WeaponId) || "rifle", clamp(pv.mul, 0.05, 0.6), pv.pan);
      } else if (m.t === "hit" && m.tgt === myId) {
        {
          // victim-side validation: bound damage, rate-limit, range + LOS sanity vs claimed attacker
          const dmg = Number(m.dmg) || 0;
          if (dmg <= 0 || dmg > 200) return;
          const nowMs = performance.now();
          if (nowMs - lastHitAcceptT < 40) return;
          const atk = peers.get(mid);
          if (atk) {
            const dist = atk.g.position.distanceTo(P.pos);
            if (dist > 280) return;
            const aEye = atk.g.position.clone().setY(atk.g.position.y + 1.6);
            const pEye = P.pos.clone().setY(P.pos.y + 1.2);
            if (dist > 4 && !losClear(aEye, pEye)) return;
          }
          lastHitAcceptT = nowMs;
          damagePlayer(dmg, String(m.fn || "ENEMY"), mid, String(m.w || "AK-103"), !!m.h, atk ? atk.g.position.clone() : undefined);
        }
      } else if (m.t === "death") {
        if (m.killer === myId) {
          onPlayerKill(!!m.h);
          P.kills++; ST.streak++; ST.bestStreak = Math.max(ST.bestStreak, ST.streak);
          P.money = Math.min(9000, P.money + 300);
          sHitmark();
        }
        killFeed(m.killer === myId ? "YOU" : String(m.kn || "?"), String(m.vn || "?"), String(m.w || "AK-103"), !!m.h);
      } else if (m.t === "bs") {
        const b = bots.find((bb) => bb.netId === m.bid);
        if (b) {
          const wasAlive = b.alive;
          b.ntx = Number(m.x) || 0; b.ntz = Number(m.z) || 0; b.ntyaw = Number(m.yaw) || 0;
          b.nmoving = !!m.mv; b.naiming = !!m.am;
          if (b.nseen === 0) { b.g.position.set(b.ntx, 0, b.ntz); }
          b.nseen = performance.now();
          if (wasAlive && !m.a && b.g.visible) charDie(b.vis);
          if (!wasAlive && !!m.a) { b.g.visible = true; charRevive(b.vis); }
          b.alive = !!m.a;
          b.hp = b.alive ? 100 : 0;
        }
      } else if (m.t === "drop") {
        spawnDrop((m.w as WeaponId) || "rifle", Number(m.x) || 0, Number(m.z) || 0);
      } else if (m.t === "nade") {
        throwNade(m.k === "smoke" ? "smoke" : "frag", new THREE.Vector3(Number(m.fx), Number(m.fy), Number(m.fz)), new THREE.Vector3(Number(m.dx), Number(m.dy), Number(m.dz)), false);
      } else if (m.t === "rdy") {
        readySet.add(mid);
        const pr = peers.get(mid); if (pr) pr.ready = true;
      } else if (m.t === "lb") {
        if (!isHost) { const lim = Number(m.lim); if (lim >= 5 && lim <= 100) dmLimit = lim; }
      } else if (m.t === "begin") {
        const lim = Number(m.lim); if (lim >= 5 && lim <= 100) dmLimit = lim;
        beginMatch();
      } else if (m.t === "rmv") {
        rmVotes.add(mid);
        const need = peers.size + 1;
        elMeRematch.textContent = "REMATCH (" + rmVotes.size + "/" + need + ")";
        if (isHost && rmVotes.size >= need) { bcSend({ t: "rm" }); resetMatch(); centerMsg("REMATCH", 1.6, "#ffd76e"); }
      } else if (m.t === "rm") {
        resetMatch();
        centerMsg("REMATCH", 1.6, "#ffd76e");
      } else if (m.t === "bhit") {
        if (isHost) {
          const b = bots.find((bb) => bb.netId === m.bid);
          const dmg = Number(m.dmg) || 0;
          if (b && dmg > 0 && dmg <= 200) {
            const atk = peers.get(mid);
            if (!atk || atk.g.position.distanceTo(b.g.position) <= 280) {
              hostDamageBot(b, dmg, String(m.fn || "PEER"), mid);
            }
          }
        }
      } else if (m.t === "leave") {
        const p = peers.get(mid);
        if (p) centerMsg(p.name + " LEFT", 1.6, "#9aa3ad");
        removePeer(mid);
      }
  }
  function netInit() {
    becomeHost();
    window.addEventListener("beforeunload", () => bcSend({ t: "leave" }), sig);
  }
  let botStateAcc = 0;
  function mpTick(dt: number) {
    stateAcc += dt;
    if (stateAcc >= 0.05) { stateAcc = 0; sendState(); }
    if (isHost) {
      botStateAcc += dt;
      if (botStateAcc >= 0.05) {
        botStateAcc = 0;
        for (const b of bots) {
          bcSend({ t: "bs", bid: b.netId, x: b.g.position.x, z: b.g.position.z, yaw: b.yaw, a: b.alive, n: b.name, mv: b.path.length > 0 || b.seeT > 0.1, am: b.seeT > 0.2 });
        }
      }
    } else {
      // client-side bot interpolation from host states
      const now2 = performance.now();
      for (const b of bots) {
        if (b.nseen === 0) continue;
        if (now2 - b.nseen > 4000) { b.g.visible = false; continue; }
        if (!b.alive) { if (b.g.visible && b.vis.mixer) { animChar(b.vis, dt, 0, false, false); } continue; }
        const k = 1 - Math.exp(-14 * dt);
        const ox = b.g.position.x, oz = b.g.position.z;
        b.g.position.x += (b.ntx - b.g.position.x) * k;
        b.g.position.z += (b.ntz - b.g.position.z) * k;
        b.g.position.y = groundHeightAt(b.g.position.x, b.g.position.z);
        let dyy = b.ntyaw - b.g.rotation.y;
        while (dyy > Math.PI) dyy -= Math.PI * 2;
        while (dyy < -Math.PI) dyy += Math.PI * 2;
        b.g.rotation.y += dyy * k;
        b.yaw = b.g.rotation.y;
        const sp = dt > 0 ? Math.hypot(b.g.position.x - ox, b.g.position.z - oz) / dt : 0;
        animChar(b.vis, dt, b.nmoving ? Math.max(sp, 2.5) : sp, b.naiming && b.nmoving, b.naiming);
      }
    }
    const now = performance.now();
    for (const [id, p] of [...peers]) {
      if (now - p.lastSeen > 4000) { removePeer(id); continue; }
      const k = 1 - Math.exp(-14 * dt);
      const ox = p.g.position.x, oz = p.g.position.z;
      p.g.position.x += (p.tx - p.g.position.x) * k;
      p.g.position.y += (p.ty - p.g.position.y) * k;
      p.g.position.z += (p.tz - p.g.position.z) * k;
      // estimate smoothed velocity from interpolation delta
      if (dt > 0) {
        p.vx = damp(p.vx, (p.g.position.x - ox) / dt, 10, dt);
        p.vz = damp(p.vz, (p.g.position.z - oz) / dt, 10, dt);
      }
      let dy = p.tyaw - p.g.rotation.y;
      while (dy > Math.PI) dy -= Math.PI * 2;
      while (dy < -Math.PI) dy += Math.PI * 2;
      p.g.rotation.y += dy * k;
      if (p.alive) p.g.rotation.x = damp(p.g.rotation.x, 0, 12, dt);
      const pSpeed = Math.hypot(p.vx, p.vz);
      animChar(p.vis, dt, p.alive ? pSpeed : 0, performance.now() - p.lastShotT < 350, performance.now() - p.lastShotT < 1500);
    }
    if (!P.alive) {
      respawnT -= dt;
      elRespawn.textContent = "ELIMINATED — respawning in " + Math.max(0, respawnT).toFixed(1) + "s";
      if (respawnT <= 0) mpRespawn();
    }
    elScoreMe.textContent = String(P.kills);
    let best = 0;
    peers.forEach((p) => { best = Math.max(best, p.kills); });
    elScoreEn.textContent = String(best);
    if (warmup) {
      lobbyAcc += dt;
      if (lobbyAcc > 0.25) {
        lobbyAcc = 0;
        const rows: { n: string; r: boolean; me: boolean }[] = [
          { n: myName + (isHost ? " (HOST)" : ""), r: isHost || myReady, me: true },
          ...[...peers.values()].map((pp) => ({ n: pp.name, r: readySet.has(pp.id) || pp.ready === true, me: false })),
        ];
        elLobbyRows.innerHTML = rows.map((r) =>
          `<div style="display:flex;justify-content:space-between;gap:24px;padding:3px 0"><span style="color:${r.me ? "#7fb3ff" : "#e8e3d6"}">${r.n}</span><span style="color:${r.r ? "#9fe870" : "#717a84"}">${r.r ? "READY" : "..."}</span></div>`).join("");
        const readyCount = rows.filter((r) => r.r).length;
        elLobbyHint.textContent = isHost
          ? (peers.size === 0 ? "SHARE THE INVITE LINK — ENTER TO START ANYWAY" : readyCount >= rows.length ? "ALL READY — PRESS ENTER TO START" : "ENTER TO FORCE START (" + readyCount + "/" + rows.length + " READY)")
          : myReady ? "WAITING FOR HOST — FRAG LIMIT " + dmLimit : "PRESS ENTER WHEN READY";
        elLobby.style.display = "block";
        if (isHost) bcSend({ t: "lb", lim: dmLimit });
      }
    } else if (!matchOver) {
      dmTime -= dt;
      if (P.kills >= dmLimit) showMatchEnd(true);
      else if (best >= dmLimit) showMatchEnd(false);
      else if (dmTime <= 0) showMatchEnd(P.kills >= best);
    }
  }
  function beginMatch() {
    if (!warmup) return;
    warmup = false;
    elLobby.style.display = "none";
    resetMatch();
    centerMsg("MATCH LIVE — FIRST TO " + dmLimit, 2.4, "#9fe870");
    sBuy();
  }
  function startMP() {
    owned.rifle = true; owned.smg = true; owned.sniper = true;
    ammo.rifle = { mag: WEAPONS.rifle.mag, res: WEAPONS.rifle.reserve };
    ammo.smg = { mag: WEAPONS.smg.mag, res: WEAPONS.smg.reserve };
    ammo.sniper = { mag: WEAPONS.sniper.mag, res: WEAPONS.sniper.reserve };
    cur = "rifle"; refreshVM();
    phase = "live"; phaseT = 1e9;
    netInit();
    bots.forEach((b) => {
      const sp = randomSpawn();
      b.g.position.set(sp[0], 0, sp[1]);
      b.hp = 100; b.alive = true; b.g.visible = true;
      b.path = []; b.pathI = 0; b.repathT = rand(0, 1); b.seeT = 0;
      b.burstLeft = 0; b.burstCd = rand(0.5, 1.5); b.yaw = rand(-3, 3); b.respawnT = 0;
      b.reactT = 0; b.wasSee = false; b.nadeCd = rand(8, 16); b.coverT = 0; b.coverCd = 0;
      b.holdT = 0; b.hasGoal = false; b.plantT = 0; b.defuseT = 0; b.carrier = false;
    });
    mpRespawn();
    centerMsg("DEATHMATCH — ROOM " + room, 3, "#ffd76e");
  }



  // ============ ragdoll (Verlet integration + distance constraints) ============
  // Jakobsen GDC2001 approach: believable, stable, no physics engine needed.
  interface RagPoint { x: number; y: number; z: number; px: number; py: number; pz: number; r: number }
  interface RagStick { a: number; b: number; len: number }
  interface Ragdoll {
    pts: RagPoint[]; sticks: RagStick[]; ttl: number;
    meshes: { m: THREE.Object3D; a: number; b: number }[]; // mesh follows segment a->b
    group: THREE.Group;
  }
  const ragdolls: Ragdoll[] = [];
  // point layout: 0 head, 1 chest, 2 hip, 3 kneeL, 4 footL, 5 kneeR, 6 footR, 7 elbowL, 8 handL, 9 elbowR, 10 handR
  function spawnRagdoll(origin: THREE.Vector3, yaw: number, impulse: THREE.Vector3, bodyM: THREE.Material, vestM: THREE.Material, pantsM: THREE.Material) {
    if (ragdolls.length >= 5) {
      const old = ragdolls.shift()!;
      scene.remove(old.group);
    }
    const sinY = Math.sin(yaw), cosY = Math.cos(yaw);
    const local: [number, number, number, number][] = [
      [0, 1.66, 0, 0.13],      // head
      [0, 1.32, 0, 0.2],       // chest
      [0, 0.94, 0, 0.18],      // hip
      [-0.14, 0.48, 0, 0.1],   // knee L
      [-0.16, 0.06, 0.04, 0.09], // foot L
      [0.14, 0.48, 0, 0.1],    // knee R
      [0.16, 0.06, 0.04, 0.09], // foot R
      [-0.4, 1.18, 0, 0.08],   // elbow L
      [-0.44, 0.84, 0, 0.07],  // hand L
      [0.4, 1.18, 0, 0.08],    // elbow R
      [0.44, 0.84, 0, 0.07],   // hand R
    ];
    const pts: RagPoint[] = local.map(([lx, ly, lz, r]) => {
      const wx = origin.x + lx * cosY + lz * sinY;
      const wz = origin.z - lx * sinY + lz * cosY;
      const wy = origin.y + ly;
      // impulse: shot direction + upward pop, head gets extra whip
      const imp = 1 + (ly > 1.5 ? 0.55 : 0) + rand(-0.12, 0.12);
      return {
        x: wx, y: wy, z: wz,
        px: wx - impulse.x * 0.016 * imp - rand(-0.004, 0.004),
        py: wy - (impulse.y * 0.016 + 0.02) * imp,
        pz: wz - impulse.z * 0.016 * imp - rand(-0.004, 0.004),
        r,
      };
    });
    const link = (a: number, b: number): RagStick => {
      const dx = pts[a].x - pts[b].x, dy = pts[a].y - pts[b].y, dz = pts[a].z - pts[b].z;
      return { a, b, len: Math.hypot(dx, dy, dz) };
    };
    const sticks: RagStick[] = [
      link(0, 1), link(1, 2),                       // spine
      link(2, 3), link(3, 4), link(2, 5), link(5, 6), // legs
      link(1, 7), link(7, 8), link(1, 9), link(9, 10), // arms
      link(0, 2),                                    // head-hip brace (keeps spine stiff-ish)
      link(3, 5),                                    // knee spacing
    ];
    // visual: simple capsule-ish limbs matching soldier palette
    const group = new THREE.Group();
    const meshes: { m: THREE.Object3D; a: number; b: number }[] = [];
    function seg(a: number, b: number, w: number, mat: THREE.Material) {
      const st = sticks.find((k) => (k.a === a && k.b === b) || (k.a === b && k.b === a));
      const ln = st ? st.len : 0.3;
      const m = new THREE.Mesh(new THREE.BoxGeometry(w, ln, w), mat);
      m.castShadow = true;
      group.add(m);
      meshes.push({ m, a, b });
    }
    seg(0, 1, 0.21, vestM);    // head/neck block
    seg(1, 2, 0.32, bodyM);    // torso
    seg(2, 3, 0.17, pantsM); seg(3, 4, 0.15, pantsM);
    seg(2, 5, 0.17, pantsM); seg(5, 6, 0.15, pantsM);
    seg(1, 7, 0.12, bodyM); seg(7, 8, 0.1, bodyM);
    seg(1, 9, 0.12, bodyM); seg(9, 10, 0.1, bodyM);
    // head cube on top point
    const headM = new THREE.Mesh(new THREE.BoxGeometry(0.24, 0.26, 0.24), headMats);
    headM.castShadow = true;
    group.add(headM);
    meshes.push({ m: headM, a: 0, b: 0 });
    scene.add(group);
    ragdolls.push({ pts, sticks, ttl: 6, meshes, group });
  }
  const RAG_ITER = 3;
  function updateRagdolls(dt: number) {
    for (let ri = ragdolls.length - 1; ri >= 0; ri--) {
      const r = ragdolls[ri];
      r.ttl -= dt;
      if (r.ttl <= 0) {
        scene.remove(r.group);
        r.group.traverse((o) => { if (o instanceof THREE.Mesh) o.geometry.dispose(); });
        ragdolls.splice(ri, 1);
        continue;
      }
      const sub = Math.min(dt, 1 / 30);
      // verlet integrate
      for (const p of r.pts) {
        const vx = (p.x - p.px) * 0.985, vy = (p.y - p.py) * 0.985, vz = (p.z - p.pz) * 0.985;
        p.px = p.x; p.py = p.y; p.pz = p.z;
        p.x += vx; p.y += vy - 11 * sub * sub; p.z += vz;
      }
      // constraints
      for (let it = 0; it < RAG_ITER; it++) {
        for (const st of r.sticks) {
          const a = r.pts[st.a], b = r.pts[st.b];
          const dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
          const d = Math.hypot(dx, dy, dz) || 1e-6;
          const diff = (d - st.len) / d * 0.5;
          a.x += dx * diff; a.y += dy * diff; a.z += dz * diff;
          b.x -= dx * diff; b.y -= dy * diff; b.z -= dz * diff;
        }
        // ground + friction
        for (const p of r.pts) {
          const gy = groundHeightAt(p.x, p.z) + p.r;
          if (p.y < gy) {
            p.y = gy;
            // ground friction: damp horizontal sliding
            p.px = p.x - (p.x - p.px) * 0.55;
            p.pz = p.z - (p.z - p.pz) * 0.55;
          }
          p.x = clamp(p.x, -50, 50);
          p.z = clamp(p.z, -50, 50);
        }
      }
      // map meshes to segments
      const va = new THREE.Vector3(), vb = new THREE.Vector3(), up = new THREE.Vector3(0, 1, 0);
      const fade = clamp(r.ttl / 0.8, 0, 1);
      for (const seg of r.meshes) {
        const a = r.pts[seg.a], b = r.pts[seg.b];
        if (seg.a === seg.b) {
          seg.m.position.set(a.x, a.y, a.z);
        } else {
          va.set(a.x, a.y, a.z); vb.set(b.x, b.y, b.z);
          seg.m.position.copy(va).add(vb).multiplyScalar(0.5);
          const dir = vb.sub(va);
          const len = dir.length();
          if (len > 1e-5) seg.m.quaternion.setFromUnitVectors(up, dir.normalize());
        }
        seg.m.scale.setScalar(Math.max(0.01, fade < 1 ? fade : 1));
      }
    }
  }


  // ============ grenades ============
  interface Nade {
    kind: "frag" | "smoke"; mesh: THREE.Mesh;
    vx: number; vy: number; vz: number; fuse: number; mine: boolean; team: "blue" | "red";
  }
  interface SmokeCloud { g: THREE.Group; ttl: number; x: number; z: number }
  const nades: Nade[] = [];
  const smokeClouds: SmokeCloud[] = [];
  const fragGeo = new THREE.SphereGeometry(0.09, 8, 8);
  const fragMat = new THREE.MeshStandardMaterial({ color: 0x2e4434, roughness: 0.5, metalness: 0.5 });
  const smokeBodyMat = new THREE.MeshStandardMaterial({ color: 0x4a5560, roughness: 0.5, metalness: 0.5 });
  const smokePuffMat = new THREE.SpriteMaterial({ map: dotTex, color: 0xc8ccd0, transparent: true, opacity: 0.92, depthWrite: false });
  function smokeBlocksLOS(a: THREE.Vector3, b: THREE.Vector3): boolean {
    // segment-vs-sphere test against each live cloud (r=3.4)
    for (const c of smokeClouds) {
      if (c.ttl < 1) continue;
      const cx = c.x, cz = c.z, cy = 1.4;
      const ab = tmpV.set(b.x - a.x, b.y - a.y, b.z - a.z);
      const len2 = ab.lengthSq();
      if (len2 < 1e-6) continue;
      const t = clamp(((cx - a.x) * ab.x + (cy - a.y) * ab.y + (cz - a.z) * ab.z) / len2, 0, 1);
      const dx = a.x + ab.x * t - cx, dy2 = a.y + ab.y * t - cy, dz = a.z + ab.z * t - cz;
      if (dx * dx + dy2 * dy2 + dz * dz < 3.4 * 3.4) return true;
    }
    return false;
  }
  function spawnSmokeCloud(x: number, y: number, z: number) {
    const g = new THREE.Group();
    for (let i = 0; i < 14; i++) {
      const p = new THREE.Sprite(smokePuffMat.clone());
      p.position.set(rand(-2.2, 2.2), rand(0.3, 2.6), rand(-2.2, 2.2));
      p.scale.setScalar(rand(2.6, 4.2));
      (p.material as THREE.SpriteMaterial).rotation = rand(0, Math.PI * 2);
      g.add(p);
    }
    g.position.set(x, y, z);
    scene.add(g);
    smokeClouds.push({ g, ttl: 12, x, z });
    burst(0.6, 600, 0.3);
  }
  function explodeFrag(pos: THREE.Vector3, mine: boolean, team: "blue" | "red" = "red") {
    // boom: light, particles, trauma by distance, radial damage with LOS check
    spawnParticles(pos, 26, 0xffb056, 7, 0.22, 0.6, 6);
    spawnParticles(pos, 18, 0x55504a, 4, 0.3, 1.1, 2);
    flashLight.position.copy(pos); flashLight.intensity = 14;
    burst(0.5, 700, 0.9); blip(60, 0.3, 0.6, "sawtooth"); burst(1.1, 320, 0.3, 0.1);
    const dCam = camera.position.distanceTo(pos);
    addTrauma(clamp(1.6 - dCam / 14, 0, 0.85));
    rumble(clamp(1.5 - dCam / 16, 0, 1), 0.6, 280);
    const R2 = 7;
    const hurt = (tp: THREE.Vector3): number => {
      const d = tp.distanceTo(pos);
      if (d > R2) return 0;
      if (!losClear(pos.clone().setY(pos.y + 0.2), tp.clone().setY(tp.y + 1))) return 0;
      return Math.round(95 * (1 - d / R2));
    };
    if (mine) {
      for (const b of bots) {
        if (!b.alive || b.team === "blue") continue; // no friendly fire
        const dmg = hurt(b.g.position);
        if (dmg > 0) {
          if (mp && !isHost) bcSend({ t: "bhit", bid: b.netId, dmg, fn: myName });
          else damageBot(b, dmg, false, "FRAG");
        }
      }
      if (mp) peers.forEach((p) => {
        if (!p.alive) return;
        const dmg = hurt(p.g.position);
        if (dmg > 0) bcSend({ t: "hit", tgt: p.id, dmg, fn: myName, w: "FRAG", h: false });
      });
    } else if (!mp) {
      // bot-thrown frag: hurts the opposite team's bots
      for (const b of bots) {
        if (!b.alive || b.team === team) continue;
        const dmg = hurt(b.g.position);
        if (dmg > 0) hostDamageBot(b, dmg, "FRAG");
      }
    }
    if (P.alive && (mine || mp || team === "red")) {
      const selfDmg = hurt(P.pos);
      if (selfDmg > 0) damagePlayer(selfDmg, mine ? "YOURSELF" : "ENEMY FRAG", undefined, "FRAG", false, pos.clone());
    }
  }
  function throwNade(kind: "frag" | "smoke", from: THREE.Vector3, dir: THREE.Vector3, mine: boolean, team: "blue" | "red" = "red") {
    const mesh = new THREE.Mesh(kind === "frag" ? fragGeo : new THREE.CylinderGeometry(0.07, 0.07, 0.18, 8), kind === "frag" ? fragMat : smokeBodyMat);
    mesh.position.copy(from);
    mesh.castShadow = true;
    scene.add(mesh);
    nades.push({ kind, mesh, vx: dir.x * 14, vy: dir.y * 14 + 3.2, vz: dir.z * 14, fuse: kind === "frag" ? 1.7 : 1.4, mine, team: mine ? "blue" : team });
    if (mine && mp) bcSend({ t: "nade", k: kind, fx: from.x, fy: from.y, fz: from.z, dx: dir.x, dy: dir.y, dz: dir.z });
  }
  function updateNades(dt: number) {
    for (let i = nades.length - 1; i >= 0; i--) {
      const n = nades[i];
      n.fuse -= dt;
      n.vy -= 13 * dt;
      const m = n.mesh.position;
      m.x += n.vx * dt; m.y += n.vy * dt; m.z += n.vz * dt;
      n.mesh.rotation.x += dt * 9; n.mesh.rotation.z += dt * 7;
      const gh = groundHeightAt(m.x, m.z) + 0.09;
      if (m.y < gh) {
        m.y = gh; n.vy = Math.abs(n.vy) * 0.42; n.vx *= 0.7; n.vz *= 0.7;
        if (Math.abs(n.vy) > 1) { const pv3 = panVol(m); if (!playSample("clank", 0.22 * pv3.mul, pv3.pan, rand(1.3, 1.6))) burst(0.04, 1400, 0.12); }
      }
      // wall bounce (cheap: test collide displacement)
      const before = tmpV.set(m.x, m.y, m.z);
      collide(m, 0.18);
      if (Math.abs(m.x - before.x) > 0.001) n.vx = -n.vx * 0.5;
      if (Math.abs(m.z - before.z) > 0.001) n.vz = -n.vz * 0.5;
      if (n.fuse <= 0) {
        if (n.kind === "frag") explodeFrag(m.clone(), n.mine, n.team);
        else spawnSmokeCloud(m.x, groundHeightAt(m.x, m.z), m.z);
        scene.remove(n.mesh);
        nades.splice(i, 1);
      }
    }
    for (let i = smokeClouds.length - 1; i >= 0; i--) {
      const c = smokeClouds[i];
      c.ttl -= dt;
      const fade = clamp(c.ttl / 2, 0, 1) * clamp((12 - c.ttl) * 2, 0, 1);
      c.g.children.forEach((p, idx2) => {
        const sp = p as THREE.Sprite;
        (sp.material as THREE.SpriteMaterial).opacity = 0.92 * fade;
        sp.position.y += Math.sin(gameT * 0.7 + idx2) * 0.0015;
        (sp.material as THREE.SpriteMaterial).rotation += dt * 0.05 * (idx2 % 2 ? 1 : -1);
      });
      if (c.ttl <= 0) {
        c.g.children.forEach((p) => ((p as THREE.Sprite).material as THREE.SpriteMaterial).dispose());
        scene.remove(c.g);
        smokeClouds.splice(i, 1);
      }
    }
  }


  // ============ weapon drops ============
  interface Drop { id: WeaponId; g: THREE.Group; ttl: number; bobOff: number }
  const drops: Drop[] = [];
  function spawnDrop(weapon: WeaponId, x: number, z: number, allowPistol = false) {
    if (weapon === "knife") return;
    if (weapon === "pistol" && !allowPistol) return;
    if (drops.length >= 6) { const old = drops.shift()!; scene.remove(old.g); }
    const g = new THREE.Group();
    const lib = gunLib[weapon];
    if (lib) {
      const gm = lib.clone(true);
      const gb = new THREE.Box3().setFromObject(gm);
      const glen = Math.max(gb.max.x - gb.min.x, gb.max.z - gb.min.z, 0.01);
      const gsc = (weapon === "sniper" ? 1.0 : weapon === "rifle" ? 0.85 : 0.62) / glen;
      gm.scale.setScalar(gsc);
      const gb2 = new THREE.Box3().setFromObject(gm);
      gm.position.set(-(gb2.max.x + gb2.min.x) / 2, 0.22 - (gb2.max.y + gb2.min.y) / 2, -(gb2.max.z + gb2.min.z) / 2);
      gm.traverse((o) => { if (o instanceof THREE.Mesh) o.castShadow = true; });
      g.add(gm);
    } else {
      const body = new THREE.Mesh(new THREE.BoxGeometry(0.08, 0.1, weapon === "sniper" ? 0.95 : weapon === "rifle" ? 0.7 : 0.5), new THREE.MeshStandardMaterial({ color: 0x23262b, roughness: 0.45, metalness: 0.6 }));
      body.castShadow = true;
      body.rotation.z = Math.PI / 2.4;
      body.position.y = 0.1;
      g.add(body);
    }
    const ring = new THREE.Mesh(new THREE.RingGeometry(0.45, 0.55, 24), new THREE.MeshBasicMaterial({ color: 0xffd76e, transparent: true, opacity: 0.55, side: THREE.DoubleSide }));
    ring.rotation.x = -Math.PI / 2;
    ring.position.y = 0.04;
    g.add(ring);
    g.position.set(x, groundHeightAt(x, z) + 0.12, z);
    g.rotation.y = rand(0, Math.PI * 2);
    scene.add(g);
    drops.push({ id: weapon, g, ttl: 25, bobOff: rand(0, 6) });
  }
  function updateDrops(dt: number) {
    for (let i = drops.length - 1; i >= 0; i--) {
      const d = drops[i];
      d.ttl -= dt;
      if (d.ttl <= 0) { scene.remove(d.g); drops.splice(i, 1); continue; }
      d.g.rotation.y += dt * 1.4;
      const ring = d.g.children[1] as THREE.Mesh;
      (ring.material as THREE.MeshBasicMaterial).opacity = (0.4 + Math.sin(gameT * 4 + d.bobOff) * 0.18) * clamp(d.ttl / 2, 0, 1);
      if (P.alive && Math.hypot(P.pos.x - d.g.position.x, P.pos.z - d.g.position.z) < 1.1) {
        const refill = !owned[d.id] || ammo[d.id].mag + ammo[d.id].res < WEAPONS[d.id].mag;
        owned[d.id] = true;
        if (nadeOut) holsterNade();
        lastGunWeapon = d.id;
        if (refill) ammo[d.id] = { mag: WEAPONS[d.id].mag, res: Math.floor(WEAPONS[d.id].reserve / 2) };
        switchWeapon(d.id);
        centerMsg(WEAPONS[d.id].name + " ACQUIRED", 1.3, "#ffd76e");
        sBuy(); rumble(0.3, 0.5, 60);
        scene.remove(d.g); drops.splice(i, 1);
      }
    }
  }

  // ============ collision ============
  const R = 0.42;
  function collide(pos: THREE.Vector3, height: number) {
    for (const b of walls) {
      if (pos.y >= b.max.y - 0.001 || pos.y + height <= b.min.y + 0.001) continue;
      const cx = clamp(pos.x, b.min.x, b.max.x);
      const cz = clamp(pos.z, b.min.z, b.max.z);
      const dx = pos.x - cx, dz = pos.z - cz;
      const d2 = dx * dx + dz * dz;
      if (d2 < R * R) {
        if (d2 < 1e-8) {
          const pushes = [
            { d: pos.x - b.min.x + R, x: -1, z: 0 }, { d: b.max.x - pos.x + R, x: 1, z: 0 },
            { d: pos.z - b.min.z + R, x: 0, z: -1 }, { d: b.max.z - pos.z + R, x: 0, z: 1 },
          ].sort((u, v) => u.d - v.d)[0];
          pos.x += pushes.x * pushes.d; pos.z += pushes.z * pushes.d;
        } else {
          const d = Math.sqrt(d2);
          pos.x = cx + (dx / d) * R; pos.z = cz + (dz / d) * R;
        }
      }
    }
    pos.x = clamp(pos.x, -50.4, 50.4); pos.z = clamp(pos.z, -50.4, 50.4);
  }
  function groundHeightAt(x: number, z: number): number {
    let g = 0;
    for (const b of walls) {
      if (x > b.min.x - 0.3 && x < b.max.x + 0.3 && z > b.min.z - 0.3 && z < b.max.z + 0.3) {
        if (b.max.y <= 1.7 && b.max.y > g) g = b.max.y;
      }
    }
    return g;
  }

  // ============ raycast helpers ============
  const ray = new THREE.Raycaster();
  function losClear(from: THREE.Vector3, to: THREE.Vector3): boolean {
    const dir = to.clone().sub(from);
    const dist = dir.length();
    dir.normalize();
    ray.set(from, dir); ray.far = dist - 0.1;
    return ray.intersectObjects(staticGroup.children, false).length === 0;
  }
  function castBullet(from: THREE.Vector3, dir: THREE.Vector3, maxDist: number, fromPlayer: boolean): { point: THREE.Vector3; normal: THREE.Vector3 | null; bot: Bot | null; peer: Peer | null; isHead: boolean } {
    ray.set(from, dir); ray.far = maxDist;
    let dist = maxDist;
    let point = from.clone().addScaledVector(dir, maxDist);
    let normal: THREE.Vector3 | null = null;
    const hits = ray.intersectObjects(staticGroup.children, false);
    if (hits.length) {
      dist = hits[0].distance;
      point = hits[0].point.clone();
      normal = hits[0].face ? hits[0].face.normal.clone().transformDirection(hits[0].object.matrixWorld) : null;
    }
    let hitBot: Bot | null = null, isHead = false;
    let hitPeer: Peer | null = null;
    if (fromPlayer) {
      for (const b of bots) {
        if (!b.alive || b.team === "blue") continue; // no friendly fire
        const headHits = ray.intersectObject(b.head, false);
        const hh = headHits.length ? headHits[0].distance : Infinity;
        const bh = b.hittable.reduce((acc, mm) => {
          const h = ray.intersectObject(mm, false);
          return h.length ? Math.min(acc, h[0].distance) : acc;
        }, Infinity);
        const nearest = Math.min(hh, bh);
        if (nearest < dist) {
          dist = nearest; hitBot = b; isHead = hh <= bh;
          point = from.clone().addScaledVector(dir, nearest);
          normal = null;
        }
      }
    }
    if (fromPlayer && mp) {
      for (const p of peers.values()) {
        if (!p.alive) continue;
        const headHit = ray.intersectObject(p.head, false);
        const hh = headHit.length ? headHit[0].distance : Infinity;
        const bh = p.parts.reduce((acc, mm) => {
          const h = ray.intersectObject(mm, false);
          return h.length ? Math.min(acc, h[0].distance) : acc;
        }, Infinity);
        const nearest = Math.min(hh, bh);
        if (nearest < dist) {
          dist = nearest; hitBot = null; hitPeer = p; isHead = hh <= bh;
          point = from.clone().addScaledVector(dir, nearest);
          normal = null;
        }
      }
    }
    return { point, normal, bot: hitBot, peer: hitPeer, isHead };
  }

  // ============ HUD ============
  const hud = document.createElement("div");
  hud.style.cssText = "position:absolute;inset:0;pointer-events:none;font-family:'Rajdhani','Segoe UI',sans-serif;user-select:none;overflow:hidden";
  container.appendChild(hud);
  const HUD_HTML = `
  <style>
    .hud-num{font-weight:700;font-variant-numeric:tabular-nums}
    @keyframes hitfade{0%{opacity:1}100%{opacity:0}}
    @keyframes killslide{0%{transform:translateX(30px);opacity:0}10%{transform:none;opacity:1}80%{opacity:1}100%{opacity:0}}
    .killrow{animation:killslide 3.4s forwards;background:rgba(15,20,26,.78);border:1px solid rgba(255,255,255,.1);backdrop-filter:blur(6px);padding:3px 10px;border-radius:3px;margin-top:4px;font-size:13px;letter-spacing:.4px;display:flex;gap:6px;align-items:center;justify-content:flex-end}
  </style>
  <div id="vignette" style="position:absolute;inset:0;box-shadow:inset 0 0 140px rgba(0,0,0,.45)"></div>
  <div id="deathfade" style="position:absolute;inset:0;background:radial-gradient(ellipse at center,rgba(40,0,0,.3) 0%,rgba(8,0,0,.82) 100%);opacity:0;transition:opacity .7s;pointer-events:none"></div>
  <div id="lowhp" style="position:absolute;inset:0;background:radial-gradient(ellipse at center,transparent 55%,rgba(160,0,0,.5) 100%);opacity:0;transition:opacity .3s"></div>
  <div id="dmgdir" style="position:absolute;left:50%;top:50%;width:130px;height:130px;transform:translate(-50%,-50%) rotate(0deg);opacity:0;pointer-events:none">
    <div style="position:absolute;left:50%;top:-6px;transform:translateX(-50%);width:0;height:0;border-left:16px solid transparent;border-right:16px solid transparent;border-bottom:18px solid rgba(255,60,40,.85)"></div>
  </div>
  <div id="scope" style="display:none;position:absolute;inset:0">
    <div style="position:absolute;inset:0;background:radial-gradient(circle at center, transparent 0, transparent 30vmin, rgba(0,0,0,.97) 31vmin)"></div>
    <div style="position:absolute;left:50%;top:0;bottom:0;width:1.5px;background:rgba(0,0,0,.85);transform:translateX(-50%)"></div>
    <div style="position:absolute;top:50%;left:0;right:0;height:1.5px;background:rgba(0,0,0,.85);transform:translateY(-50%)"></div>
    <div style="position:absolute;left:50%;top:50%;width:7px;height:7px;border:1.5px solid rgba(190,40,30,.9);border-radius:50%;transform:translate(-50%,-50%)"></div>
  </div>
  <canvas id="radar" width="176" height="176" style="position:absolute;top:14px;left:16px;width:176px;height:176px;border-radius:6px;border:1px solid rgba(255,255,255,.15);background:rgba(15,20,26,.8);backdrop-filter:blur(6px)"></canvas>
  <div id="dmgflash" style="position:absolute;inset:0;background:radial-gradient(ellipse at center,transparent 40%,rgba(200,0,0,.55) 100%);opacity:0;transition:opacity .1s"></div>
  <div id="xhair" style="position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:40px;height:40px">
    <div class="xl" style="position:absolute;background:#9fe870;width:2px;height:9px;left:19px;top:0"></div>
    <div class="xl" style="position:absolute;background:#9fe870;width:2px;height:9px;left:19px;bottom:0"></div>
    <div class="xl" style="position:absolute;background:#9fe870;height:2px;width:9px;top:19px;left:0"></div>
    <div class="xl" style="position:absolute;background:#9fe870;height:2px;width:9px;top:19px;right:0"></div>
  </div>
  <div id="hitmark" style="position:absolute;left:50%;top:50%;transform:translate(-50%,-50%) rotate(45deg);width:26px;height:26px;opacity:0">
    <div style="position:absolute;background:#fff;width:2px;height:8px;left:12px;top:0"></div>
    <div style="position:absolute;background:#fff;width:2px;height:8px;left:12px;bottom:0"></div>
    <div style="position:absolute;background:#fff;height:2px;width:8px;top:12px;left:0"></div>
    <div style="position:absolute;background:#fff;height:2px;width:8px;top:12px;right:0"></div>
  </div>
  <div style="position:absolute;top:14px;left:50%;transform:translateX(-50%);display:flex;gap:14px;align-items:center;background:rgba(15,20,26,.82);border:1px solid rgba(255,255,255,.12);border-bottom:2px solid #9fe870;padding:6px 20px;border-radius:3px;backdrop-filter:blur(8px)">
    <span class="hud-num" style="color:#7fb3ff;font-size:22px" id="scoreMe">0</span>
    <span id="timer" class="hud-num" style="color:#e8e3d6;font-size:18px;min-width:54px;text-align:center">1:45</span>
    <span class="hud-num" style="color:#ff9d7a;font-size:22px" id="scoreEn">0</span>
  </div>
  <div id="phaseLabel" style="position:absolute;top:64px;left:50%;transform:translateX(-50%);color:#ffd76e;font-size:15px;letter-spacing:2px;text-shadow:0 1px 3px #000"></div>
  <div style="position:absolute;left:18px;bottom:16px;display:flex;gap:10px;align-items:flex-end">
    <div style="background:rgba(15,20,26,.82);border:1px solid rgba(255,255,255,.12);border-left:3px solid #9fe870;border-radius:3px;padding:10px 16px;display:flex;gap:18px;align-items:center;backdrop-filter:blur(8px)">
      <div><div style="color:#9aa3ad;font-size:11px;letter-spacing:1px">HP</div><div id="hp" class="hud-num" style="color:#9fe870;font-size:30px;line-height:1;font-weight:700">100</div><div style="margin-top:4px;width:84px;height:4px;background:rgba(255,255,255,.12);border-radius:2px"><div id="hpbar" style="width:100%;height:100%;background:#9fe870;border-radius:2px;transition:width .15s"></div></div></div>
      <div><div style="color:#9aa3ad;font-size:11px;letter-spacing:1px">ARMOR</div><div id="armor" class="hud-num" style="color:#7fb3ff;font-size:30px;line-height:1;font-weight:700">0</div></div>
    </div>
  </div>
  <div style="position:absolute;right:18px;bottom:16px;text-align:right">
    <div style="background:rgba(15,20,26,.82);border:1px solid rgba(255,255,255,.12);border-right:3px solid #ffd76e;border-radius:3px;padding:10px 16px;backdrop-filter:blur(8px)">
      <img id="wicon" src="/tex/w_pistol.png" alt="" style="height:34px;display:block;margin-left:auto;filter:drop-shadow(0 1px 2px rgba(0,0,0,.8))" />
      <div id="wname" style="color:#e8e3d6;font-size:13px;letter-spacing:1.5px">P-57</div>
      <div><span id="mag" class="hud-num" style="color:#fff;font-size:30px">12</span><span id="res" class="hud-num" style="color:#9aa3ad;font-size:16px"> / 48</span></div>
      <div id="money" class="hud-num" style="color:#9fe870;font-size:16px">$800</div>
      <div id="nades" style="color:#9aa3ad;font-size:12px;letter-spacing:1px;margin-top:2px">G: FRAG x1 · SMOKE x1</div>
    </div>
  </div>
  <div id="feed" style="position:absolute;top:14px;right:16px;width:280px"></div>
  <div id="center" style="position:absolute;left:50%;top:34%;transform:translate(-50%,-50%);text-align:center;color:#fff;font-size:34px;font-weight:700;letter-spacing:3px;text-shadow:0 2px 8px #000"></div>
  <div id="respawn" style="position:absolute;left:50%;top:58%;transform:translateX(-50%);color:#c8ccd2;font-size:15px;letter-spacing:1px;display:none">You are dead — LMB to spectate</div>
  <div id="bombact" style="position:absolute;left:50%;top:64%;transform:translateX(-50%);width:260px;display:none;text-align:center">
    <div id="bombactlabel" style="color:#ffd76e;font-size:14px;letter-spacing:2px;margin-bottom:6px;text-shadow:0 1px 3px #000">HOLD E</div>
    <div style="height:6px;background:rgba(255,255,255,.12);border:1px solid rgba(255,255,255,.25)"><div id="bombactbar" style="height:100%;width:0%;background:#ffd76e"></div></div>
  </div>
  <div id="bombtimer" style="position:absolute;left:50%;top:64px;transform:translateX(-50%);color:#ff5f4a;font-size:20px;letter-spacing:3px;display:none;text-shadow:0 1px 4px #000" class="hud-num">C4 0:40</div>
  <div id="lobby" style="position:absolute;left:50%;top:96px;transform:translateX(-50%);min-width:300px;display:none;background:rgba(10,13,16,.82);border:1px solid rgba(255,255,255,.16);border-top:2px solid #7fb3ff;padding:14px 18px;text-align:center;backdrop-filter:blur(4px)">
    <div style="color:#7fb3ff;font-size:14px;letter-spacing:3px;font-weight:700">WARMUP LOBBY</div>
    <div id="lobbyRows" style="margin:10px 0;font-size:13px;letter-spacing:1px"></div>
    <div id="lobbyHint" style="color:#ffd76e;font-size:12px;letter-spacing:1.5px"></div>
  </div>
  <div id="buy" style="display:none;position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);background:rgba(12,16,21,.94);border:1px solid rgba(255,255,255,.15);border-top:2px solid #ffd76e;border-radius:4px;padding:22px 30px;min-width:460px;pointer-events:auto;backdrop-filter:blur(10px)">
    <div style="color:#ffd76e;font-size:19px;letter-spacing:3px;margin-bottom:4px;font-weight:700">BUY MENU</div>
    <div style="color:#9aa3ad;font-size:12px;margin-bottom:14px">Press B to close — buy phase only</div>
    <div id="buyRows"></div>
  </div>
  <div id="matchend" style="display:none;position:absolute;inset:0;background:rgba(6,8,10,.88);backdrop-filter:blur(8px);pointer-events:auto">
    <div style="max-width:520px;margin:9vh auto 0;text-align:center">
      <div id="meTitle" style="font-size:46px;font-weight:700;letter-spacing:6px;color:#9fe870;text-shadow:0 2px 16px rgba(0,0,0,.8)">VICTORY</div>
      <div id="mePodium" style="margin:26px 0 18px;text-align:left"></div>
      <div id="meStats" style="display:grid;grid-template-columns:repeat(4,1fr);gap:8px;margin-bottom:26px"></div>
      <button id="meRematch" style="cursor:pointer;border:2px solid #9fe870;background:rgba(159,232,112,.1);color:#9fe870;font-family:inherit;font-size:19px;font-weight:700;letter-spacing:4px;padding:12px 46px;cursor:pointer">REMATCH</button>
    </div>
  </div>
  <div id="board" style="display:none;position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);background:rgba(12,16,21,.94);border:1px solid rgba(255,255,255,.15);border-radius:4px;padding:20px 28px;min-width:480px;backdrop-filter:blur(10px)">
    <div style="color:#e8e3d6;font-size:17px;letter-spacing:3px;margin-bottom:12px;font-weight:700">SCOREBOARD</div>
    <div id="boardRows" style="font-size:14px"></div>
  </div>`;
  hud.innerHTML = HUD_HTML;
  const $ = <T extends HTMLElement = HTMLElement>(id: string) => hud.querySelector<T>("#" + id)!;
  const elHp = $("hp"), elArmor = $("armor"), elMag = $("mag"), elRes = $("res"), elWname = $("wname"),
    elMoney = $("money"), elTimer = $("timer"), elScoreMe = $("scoreMe"), elScoreEn = $("scoreEn"),
    elPhase = $("phaseLabel"), elFeed = $("feed"), elCenter = $("center"), elBuy = $("buy"),
    elBuyRows = $("buyRows"), elBoard = $("board"), elBoardRows = $("boardRows"),
    elDmg = $("dmgflash"), elHitmark = $("hitmark"), elXhair = $("xhair"), elRespawn = $("respawn");
  const elScope = $("scope");
  const elBombAct = $("bombact"), elBombActLabel = $("bombactlabel"), elBombActBar = $("bombactbar"), elBombTimer = $("bombtimer");
  const elLobby = $("lobby"), elLobbyRows = $("lobbyRows"), elLobbyHint = $("lobbyHint");
  const elMatchEnd = $("matchend"), elMeTitle = $("meTitle"), elMePodium = $("mePodium"),
    elMeStats = $("meStats"), elMeRematch = $("meRematch");
  // apply crosshair settings
  elXhair.style.transform = "translate(-50%,-50%) scale(" + XSIZE + ")";
  elXhair.querySelectorAll<HTMLElement>(".xl").forEach((el) => { el.style.background = XCOLOR; });
  function showMatchEnd(won: boolean) {
    if (matchOver) return;
    matchOver = true;
    saveProfile(won);
    elMeTitle.textContent = won ? "VICTORY" : "DEFEAT";
    elMeTitle.style.color = won ? "#9fe870" : "#ff6b5a";
    const rows = mp
      ? [
          { n: myName + " (YOU)", k: P.kills, d: P.deaths, me: true },
          ...[...peers.values()].map((p) => ({ n: p.name, k: p.kills, d: p.deaths, me: false })),
        ].sort((a, b) => b.k - a.k)
      : [{ n: "YOU — " + myScore + " ROUNDS", k: P.kills, d: P.deaths, me: true }, { n: "ENEMY SQUAD — " + enemyScore + " ROUNDS", k: -1, d: -1, me: false }];
    elMePodium.innerHTML = rows.map((r, i) =>
      `<div style="display:flex;justify-content:space-between;padding:9px 14px;margin:3px 0;border-radius:4px;background:${r.me ? "rgba(127,179,255,.14)" : "rgba(255,255,255,.05)"};border:1px solid rgba(255,255,255,.08)">
        <span style="color:${r.me ? "#7fb3ff" : "#e8e3d6"}">${i === 0 ? "🏆 " : ""}${r.n}</span>
        <span class="hud-num" style="color:#9aa3ad">${r.k >= 0 ? r.k + " / " + r.d : ""}</span>
      </div>`).join("");
    const acc = ST.shots > 0 ? Math.round((ST.hits / ST.shots) * 100) : 0;
    const hsr = ST.hits > 0 ? Math.round((ST.head / ST.hits) * 100) : 0;
    const stat = (label: string, val: string) =>
      `<div style="background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.08);border-radius:4px;padding:10px 6px">
        <div style="color:#9aa3ad;font-size:10px;letter-spacing:1.5px">${label}</div>
        <div class="hud-num" style="color:#ffd76e;font-size:22px">${val}</div>
      </div>`;
    elMeStats.innerHTML = stat("K / D", P.kills + " / " + P.deaths) + stat("ACCURACY", acc + "%") + stat("HEADSHOT", hsr + "%") + stat("BEST STREAK", String(ST.bestStreak));
    elMatchEnd.style.display = "block";
    document.exitPointerLock();
  }
  function resetMatch() {
    matchOver = false;
    rmVotes.clear();
    elMeRematch.textContent = "REMATCH";
    elMatchEnd.style.display = "none";
    P.kills = 0; P.deaths = 0;
    ST.shots = 0; ST.hits = 0; ST.head = 0; ST.streak = 0; ST.bestStreak = 0;
    if (mp) {
      dmTime = DM_TIME;
      peers.forEach((p) => { p.kills = 0; p.deaths = 0; });
      elScoreMe.textContent = "0"; elScoreEn.textContent = "0";
      mpRespawn();
    } else {
      myScore = 0; enemyScore = 0; round = 0;
      lossStreak = 0; roundKillsMe = 0; roundKillsBot.clear(); hasKit = false;
      bots.forEach((b) => { b.kills = 0; b.deaths = 0; });
      P.money = 800; P.armor = 0;
      owned.rifle = false; owned.smg = false; owned.sniper = false;
      elScoreMe.textContent = "0"; elScoreEn.textContent = "0";
      startRound();
    }
  }
  elMeRematch.addEventListener("click", () => {
    if (mp) {
      if (rmVotes.has(myId)) return;
      rmVotes.add(myId);
      bcSend({ t: "rmv" });
      const need = peers.size + 1;
      elMeRematch.textContent = "REMATCH (" + rmVotes.size + "/" + need + ")";
      if (isHost && rmVotes.size >= need) { bcSend({ t: "rm" }); resetMatch(); }
      return;
    }
    resetMatch();
    canvas.requestPointerLock();
  }, sig);
  const elWicon = hud.querySelector<HTMLImageElement>("#wicon")!;
  const elNades = $("nades");
  let lastIcon = "";

  function killFeed(killer: string, victim: string, weapon: string, head: boolean) {
    const row = document.createElement("div");
    row.className = "killrow";
    const icon = ICON_BY_NAME[weapon];
    const mid = icon
      ? `<img src="${icon}" alt="" style="height:16px;filter:drop-shadow(0 1px 1px rgba(0,0,0,.9))" />${head ? '<span style="color:#ffd76e;font-weight:700">HS</span>' : ""}`
      : `<span style="color:#9aa3ad">[${weapon}${head ? " · HS" : ""}]</span>`;
    row.innerHTML = `<span style="color:${isBlueName(killer) ? "#7fb3ff" : "#ff9d7a"}">${killer}</span>${mid}<span style="color:${isBlueName(victim) ? "#7fb3ff" : "#ff9d7a"}">${victim}</span>`;
    elFeed.appendChild(row);
    setTimeout(() => row.remove(), 3500);
    while (elFeed.children.length > 5) elFeed.firstChild!.remove();
  }
  let centerT = 0;
  function centerMsg(txt: string, secs = 2.2, color = "#fff") {
    elCenter.textContent = txt; elCenter.style.color = color; centerT = secs;
  }
  function updateBuyMenu() {
    const stat = (w: WeaponId) => "DMG " + WEAPONS[w].dmg + " · RPM " + WEAPONS[w].rpm + " · MAG " + WEAPONS[w].mag;
    const items: { id: WeaponId | "armor" | "frag" | "smoke" | "kit"; label: string; sub?: string; price: number; have: boolean; icon?: string; hidden?: boolean }[] = [
      { id: "smg", label: "MP-9  SMG", sub: stat("smg"), price: WEAPONS.smg.price, have: owned.smg, icon: WEAPON_ICONS.smg },
      { id: "rifle", label: "AK-103  RIFLE", sub: stat("rifle"), price: WEAPONS.rifle.price, have: owned.rifle, icon: WEAPON_ICONS.rifle },
      { id: "sniper", label: "SR-90  SNIPER", sub: stat("sniper"), price: WEAPONS.sniper.price, have: owned.sniper, icon: WEAPON_ICONS.sniper },
      { id: "armor", label: "KEVLAR VEST", sub: "absorbs 50% of damage", price: 650, have: P.armor >= 100 },
      { id: "frag", label: "FRAG GRENADE", sub: "max 2", price: FRAG_PRICE, have: frags >= 2 },
      { id: "smoke", label: "SMOKE GRENADE", sub: "max 2 · blocks bot vision", price: SMOKE_PRICE, have: smokes >= 2 },
      { id: "kit", label: "DEFUSE KIT", sub: "defuse in 3.5s instead of 7s", price: 400, have: hasKit, hidden: !(defuse && !attacking) },
    ];
    const vis2 = items.filter((it) => !it.hidden);
    elBuyRows.innerHTML = vis2.map((it, i) =>
      `<div data-buy="${it.id}" style="display:flex;align-items:center;justify-content:space-between;gap:20px;padding:8px 12px;margin:4px 0;border:1px solid rgba(255,255,255,.12);border-radius:3px;font-size:14px;cursor:${it.have || P.money < it.price ? "default" : "pointer"};opacity:${it.have || P.money < it.price ? 0.45 : 1};background:rgba(255,255,255,.04)">
        <span style="display:flex;align-items:center;gap:12px;color:#e8e3d6;letter-spacing:1px">[${i + 1}]${it.icon ? `<img src="${it.icon}" alt="" style="height:22px;width:64px;object-fit:contain" />` : '<span style="width:64px;text-align:center;color:#7fb3ff">▣</span>'}<span>${it.label}${it.sub ? `<div style="font-size:10.5px;letter-spacing:.5px;color:#717a84;margin-top:1px">${it.sub}</div>` : ""}</span></span>
        <span class="hud-num" style="color:${it.have ? "#9aa3ad" : "#9fe870"}">${it.have ? "OWNED" : "$" + it.price}</span>
      </div>`).join("");
  }
  function tryBuy(id: WeaponId | "armor" | "frag" | "smoke" | "kit") {
    if (phase !== "buy") return;
    if (id === "kit") {
      if (hasKit || P.money < 400 || !(defuse && !attacking)) { sClick(); return; }
      P.money -= 400; hasKit = true; sBuy();
      updateBuyMenu();
      return;
    }
    if (id === "armor") {
      if (P.armor >= 100 || P.money < 650) { sClick(); return; }
      P.money -= 650; P.armor = 100; sBuy();
    } else if (id === "frag") {
      if (frags >= 2 || P.money < FRAG_PRICE) { sClick(); return; }
      P.money -= FRAG_PRICE; frags++; sBuy();
    } else if (id === "smoke") {
      if (smokes >= 2 || P.money < SMOKE_PRICE) { sClick(); return; }
      P.money -= SMOKE_PRICE; smokes++; sBuy();
    } else {
      const def = WEAPONS[id as WeaponId];
      if (owned[id as WeaponId] || P.money < def.price) { sClick(); return; }
      P.money -= def.price;
      owned[id as WeaponId] = true;
      ammo[id as WeaponId] = { mag: def.mag, res: def.reserve };
      cur = id as WeaponId; refreshVM(); sBuy();
    }
    updateBuyMenu();
  }
  elBuy.addEventListener("click", (e) => {
    const t = (e.target as HTMLElement).closest("[data-buy]") as HTMLElement | null;
    if (t) tryBuy(t.dataset.buy as WeaponId | "armor" | "frag" | "smoke" | "kit");
  }, sig);
  function updateBoard() {
    const row = (r: { n: string; k: number; d: number; me: boolean; alive: boolean }) =>
      `<div style="display:flex;justify-content:space-between;padding:6px 8px;margin:2px 0;border-radius:3px;background:${r.me ? "rgba(127,179,255,.12)" : "rgba(255,255,255,.03)"};color:${r.alive ? "#e8e3d6" : "#6b7280"}">
        <span>${r.n}${r.alive ? "" : " 💀"}</span><span class="hud-num">${r.k + " / " + r.d}</span>
      </div>`;
    const head = (txt: string, color: string) =>
      `<div style="display:flex;justify-content:space-between;color:${color};font-size:11px;letter-spacing:2px;padding:6px 8px 2px">${txt}<span>K / D</span></div>`;
    if (mp) {
      const rows = [
        { n: myName + " (YOU)", k: P.kills, d: P.deaths, me: true, alive: P.alive },
        ...[...peers.values()].map((p) => ({ n: p.name, k: p.kills, d: p.deaths, me: false, alive: p.alive })),
      ].sort((a, b) => b.k - a.k);
      elBoardRows.innerHTML = head("PLAYER", "#9aa3ad") + rows.map(row).join("");
      return;
    }
    const blue = [
      { n: myName + " (YOU)", k: P.kills, d: P.deaths, me: true, alive: P.alive },
      ...bots.filter((b) => b.team === "blue").map((b) => ({ n: b.name, k: b.kills, d: b.deaths, me: false, alive: b.alive })),
    ].sort((a, b) => b.k - a.k);
    const red = bots.filter((b) => b.team === "red").map((b) => ({ n: b.name, k: b.kills, d: b.deaths, me: false, alive: b.alive })).sort((a, b) => b.k - a.k);
    elBoardRows.innerHTML =
      head("BLUE — YOUR SQUAD", "#7fb3ff") + blue.map(row).join("") +
      head("RED — ENEMY SQUAD", "#ff9d7a") + red.map(row).join("");
  }

  // ============ combat ============
  let hitmarkT = 0, dmgT = 0, dmgDirT = 0, dmgDirAngle = 0;
  const elRadar = hud.querySelector<HTMLCanvasElement>("#radar")!;
  const radarCtx = elRadar.getContext("2d")!;
  const elDmgDir = $("dmgdir"), elLowHp = $("lowhp"), elHpBar = $("hpbar"), elDeathFade = $("deathfade");
  const RADAR_RANGE = 55;
  function drawRadar() {
    const W = 176, C = W / 2;
    radarCtx.clearRect(0, 0, W, W);
    radarCtx.save();
    radarCtx.beginPath(); radarCtx.rect(3, 3, W - 6, W - 6); radarCtx.clip();
    radarCtx.imageSmoothingEnabled = false;
    // rings + cross
    radarCtx.strokeStyle = "rgba(159,232,112,.16)";
    radarCtx.lineWidth = 1;
    for (const r of [28, 56, 84]) { radarCtx.strokeRect(C - r, C - r, r * 2, r * 2); }
    radarCtx.beginPath(); radarCtx.moveTo(C, 6); radarCtx.lineTo(C, W - 6); radarCtx.moveTo(6, C); radarCtx.lineTo(W - 6, C); radarCtx.stroke();
    // map walls (rotate so player-forward = up)
    const cosY = Math.cos(P.yaw), sinY = Math.sin(P.yaw);
    const toRadar = (wx: number, wz: number): [number, number] => {
      const dx = wx - P.pos.x, dz = wz - P.pos.z;
      const rx = dx * cosY - dz * sinY;
      const rz = -(dx * sinY + dz * cosY);
      return [C + (rx / RADAR_RANGE) * (C - 6), C - (rz / RADAR_RANGE) * (C - 6)];
    };
    radarCtx.fillStyle = "rgba(200,190,160,.22)";
    for (const r of mapRects) {
      const corners = [
        toRadar(r.x - r.w / 2, r.z - r.d / 2), toRadar(r.x + r.w / 2, r.z - r.d / 2),
        toRadar(r.x + r.w / 2, r.z + r.d / 2), toRadar(r.x - r.w / 2, r.z + r.d / 2),
      ];
      radarCtx.beginPath();
      radarCtx.moveTo(corners[0][0], corners[0][1]);
      for (let i = 1; i < 4; i++) radarCtx.lineTo(corners[i][0], corners[i][1]);
      radarCtx.closePath(); radarCtx.fill();
    }
    // enemies / peers
    const blip = (wx: number, wz: number, color: string) => {
      const [px, py] = toRadar(wx, wz);
      radarCtx.fillStyle = color;
      radarCtx.fillRect(Math.round(px) - 3, Math.round(py) - 3, 6, 6);
    };
    if (mp) {
      peers.forEach((p) => { if (p.alive) blip(p.g.position.x, p.g.position.z, "#ff5f4a"); });
      for (const b of bots) if (b.alive && b.g.visible) blip(b.g.position.x, b.g.position.z, "#ffb04a");
    } else for (const b of bots) {
      if (!b.alive) continue;
      if (b.team === "blue") blip(b.g.position.x, b.g.position.z, "#7fb3ff");
      else if (b.seeT > 0.05 || b.shootT > 0) blip(b.g.position.x, b.g.position.z, "#ff5f4a");
    }
    // site labels (always — map identity) + bomb (defuse)
    {
      radarCtx.font = "bold 11px Rajdhani, sans-serif";
      radarCtx.textAlign = "center"; radarCtx.textBaseline = "middle";
      for (const k of ["A", "B"] as const) {
        const st = SITES[k];
        const [sx, sy] = toRadar(st.x, st.z);
        if (sx > 4 && sx < W - 4 && sy > 4 && sy < W - 4) {
          const col = k === "A" ? "255,215,110" : "127,179,255";
          radarCtx.fillStyle = "rgba(" + col + ",.25)";
          radarCtx.beginPath(); radarCtx.arc(sx, sy, 9, 0, 6.29); radarCtx.fill();
          radarCtx.fillStyle = "rgb(" + col + ")";
          radarCtx.fillText(k, sx, sy + 0.5);
        }
      }
      if (defuse && (bombState === "dropped" || bombState === "planted")) {
        const [bx, by] = toRadar(bombX, bombZ);
        if (bx > 2 && bx < W - 2 && by > 2 && by < W - 2) {
          radarCtx.fillStyle = bombState === "planted" && Math.sin(gameT * 8) > 0 ? "#ff2f1a" : "#ff7a4a";
          radarCtx.beginPath(); radarCtx.arc(bx, by, 4, 0, 6.29); radarCtx.fill();
          radarCtx.fillStyle = "#fff"; radarCtx.font = "bold 8px sans-serif";
          radarCtx.fillText("C4", bx, by + 0.5);
        }
      }
    }
    // live grenades + smoke clouds
    for (const n of nades) {
      const [nx, ny] = toRadar(n.mesh.position.x, n.mesh.position.z);
      if (nx > 2 && nx < W - 2 && ny > 2 && ny < W - 2) {
        radarCtx.fillStyle = n.kind === "frag" ? "#ffb04a" : "#7fb3ff";
        radarCtx.beginPath(); radarCtx.arc(nx, ny, 2.5, 0, 6.29); radarCtx.fill();
      }
    }
    for (const c of smokeClouds) {
      if (c.ttl < 1) continue;
      const [cx2, cy2] = toRadar(c.x, c.z);
      radarCtx.fillStyle = "rgba(200,204,208,.3)";
      radarCtx.beginPath(); radarCtx.arc(cx2, cy2, (3.4 / RADAR_RANGE) * (C - 6), 0, 6.29); radarCtx.fill();
    }
    // player arrow
    radarCtx.fillStyle = "#9fe870";
    radarCtx.beginPath();
    radarCtx.moveTo(C, C - 7); radarCtx.lineTo(C - 5, C + 5); radarCtx.lineTo(C + 5, C + 5);
    radarCtx.closePath(); radarCtx.fill();
    radarCtx.restore();
  }
  let radarAcc = 0;
  function damagePlayer(dmg: number, fromName: string, killerId?: string, weapon = "AK-103", head = false, attackerPos?: THREE.Vector3) {
    if (!P.alive) return;
    let d = dmg;
    if (P.armor > 0) {
      const absorbed = Math.min(P.armor, d * 0.5);
      P.armor -= Math.ceil(absorbed); d -= absorbed;
    }
    P.hp -= Math.ceil(d);
    dmgT = 0.45; sHurt();
    addTrauma(0.32);
    rumble(0.8, 0.4, 160);
    aimPunchP += rand(0.008, 0.02);
    aimPunchY += rand(-0.012, 0.012);
    if (attackerPos) {
      const ang = Math.atan2(attackerPos.x - P.pos.x, attackerPos.z - P.pos.z);
      dmgDirAngle = (-(ang - P.yaw) * 180) / Math.PI + 180;
      dmgDirT = 1.1;
    }
    if (P.hp <= 0) {
      P.hp = 0; P.alive = false; P.deaths++;
      ST.streak = 0;
      { const kb = bots.find((x) => x.netId === killerId); if (kb) { kb.kills++; roundKillsBot.set(kb.name, (roundKillsBot.get(kb.name) || 0) + 1); } }
      if (mp && (cur === "rifle" || cur === "smg" || cur === "sniper")) {
        spawnDrop(cur, P.pos.x, P.pos.z);
        bcSend({ t: "drop", w: cur, x: P.pos.x, z: P.pos.z });
      }
      ads = false;
      killFeed(fromName, "YOU", weapon, head);
      elRespawn.style.display = "block";
      elXhair.style.display = "none";
      deathCamT = 0; deathRollDir = Math.random() < 0.5 ? -1 : 1;
      if (defuse && playerCarrier) { playerCarrier = false; bombOut = false; dropBomb(P.pos.x, P.pos.z); }
      if (bombOut) { bombOut = false; refreshVM(); }
      vmRoot.visible = false;
      elDeathFade.style.opacity = "1";
      addTrauma(0.45);
      if (mp) {
        respawnT = 2;
        bcSend({ t: "death", killer: killerId || "", kn: fromName, vn: myName, w: weapon, h: head });
        sendState();
      } else checkRoundEnd();
    }
  }
  function onPlayerKill(isHead: boolean) {
    rumble(0.5, 0.8, 90);
    hitStopT = 0.05;
    killFlashT = 0.4;
    addTrauma(0.18);
    if (gameT - lastKillT < 4) multiKills++; else multiKills = 1;
    lastKillT = gameT;
    if (multiKills >= 2) {
      const names = ["", "", "DOUBLE KILL", "TRIPLE KILL", "QUAD KILL", "RAMPAGE"];
      centerMsg(names[Math.min(multiKills, 5)], 1.6, "#ffd76e");
      blip(660 + multiKills * 110, 0.09, 0.3, "square");
      blip(880 + multiKills * 110, 0.12, 0.3, "square", 0.1);
    } else if (isHead) {
      blip(1318, 0.07, 0.25, "sine"); blip(1760, 0.09, 0.22, "sine", 0.06);
    }
    // streak milestone stingers
    const sNext = ST.streak + 1;
    if (sNext === 3 || sNext === 5 || sNext === 7) {
      setTimeout(() => {
        if (ST.streak === sNext) centerMsg(ST.streak + " KILL STREAK", 1.8, "#9fe870");
      }, 600);
    }
  }
  function damageBot(b: Bot, dmg: number, isHead: boolean, weaponName: string) {
    b.vis.hitT = 0.45;
    if (!b.alive) return;
    b.hp -= dmg;
    b.flinchT = 0.16;
    sHitmark(); hitmarkT = 0.18;
    if (b.hp <= 0) {
      const shotDir = new THREE.Vector3();
      camera.getWorldDirection(shotDir);
      shotDir.y = Math.abs(shotDir.y) * 0.3 + (isHead ? 0.5 : 0.28);
      shotDir.normalize().multiplyScalar(isHead ? 5.2 : 3.6);
      botDie(b, "YOU", isHead, shotDir);
      onPlayerKill(isHead);
      P.kills++; roundKillsMe++; ST.streak++; ST.bestStreak = Math.max(ST.bestStreak, ST.streak);
      P.money = Math.min(9000, P.money + 300);
      killFeed("YOU", b.name, weaponName, isHead);
      if (mp) bcSend({ t: "death", killer: myId, kn: myName, vn: b.name, w: weaponName, h: isHead });
      spawnParticles(b.g.position.clone().setY(1.2), 16, 0x8a1414, 3.2, 0.12, 0.5);
      checkRoundEnd();
    }
  }
  function playerShoot() {
    const def = WEAPONS[cur];
    const am = ammo[cur];
    if (!def.melee) {
      if (am.mag <= 0) { sEmpty(); if (am.res > 0) startReload(); return; }
      am.mag--;
    }
    fireT = 60 / def.rpm;
    inspectT = 0;
    if (!def.melee) ST.shots++;
    sShot(cur);
    gunKick = 1;
    addTrauma(cur === "sniper" ? 0.3 : cur === "rifle" ? 0.06 : cur === "pistol" ? 0.05 : 0.03);
    rumble(cur === "sniper" ? 0.9 : 0.25, 0.5, cur === "sniper" ? 120 : 45);
    const dir = new THREE.Vector3();
    camera.getWorldDirection(dir);
    const moving = P.vel.lengthSq() > 4 || !P.onGround;
    let spreadBase = def.melee ? 0 : def.spread + (moving ? def.moveSpread : 0) + recoilHeat * 0.012 + (P.crouching ? -0.003 : 0);
    if (cur === "sniper" && adsAmt < 0.6) spreadBase = 0.085;
    const spread = Math.max(0, spreadBase) * (1 - 0.6 * adsAmt);
    dir.x += rand(-spread, spread); dir.y += rand(-spread, spread); dir.z += rand(-spread, spread);
    dir.normalize();
    const from = camera.getWorldPosition(new THREE.Vector3());
    const res = castBullet(from, dir, def.range, true);
    if (!def.melee) {
      recoilHeat = Math.min(recoilHeat + 1, 7);
      P.pitch += def.recoil * (1 + recoilHeat * 0.35) * (1 - 0.4 * adsAmt);
      P.yaw += rand(-def.recoil, def.recoil) * 0.6;
      const muzzleWorld = (vms[cur].userData.muzzle as THREE.Object3D).getWorldPosition(new THREE.Vector3());
      muzzleFlashAt(muzzleWorld, cur === "rifle" || cur === "sniper");
      spawnParticles(muzzleWorld, 1, 0xd9b34a, 2.0, 0.025, 0.5, 11);
      spawnParticles(muzzleWorld, 2, 0x9b9b9b, 0.35, 0.05, 0.55, -0.5);
      addTracer(muzzleWorld, res.point, 0xffe6a3, 0.09);
      if (mp) bcSend({ t: "shot", fx: muzzleWorld.x, fy: muzzleWorld.y, fz: muzzleWorld.z, ex: res.point.x, ey: res.point.y, ez: res.point.z, w: cur });
    }
    if (res.bot) {
      ST.hits++; if (res.isHead) ST.head++;
      const mul = res.isHead ? def.headMul : 1;
      if (mp && !isHost) {
        bcSend({ t: "bhit", bid: res.bot.netId, dmg: Math.round(def.dmg * mul), fn: myName });
        sHitmark(); hitmarkT = 0.18;
      } else {
        damageBot(res.bot, Math.round(def.dmg * mul), res.isHead, def.name);
      }
      spawnParticles(res.point, 7, 0x8a1414, 2.4, 0.1, 0.4);
    } else if (res.peer) {
      ST.hits++; if (res.isHead) ST.head++;
      const mul = res.isHead ? def.headMul : 1;
      bcSend({ t: "hit", tgt: res.peer.id, dmg: Math.round(def.dmg * mul), fn: myName, w: def.name, h: res.isHead });
      sHitmark(); hitmarkT = 0.18;
      spawnParticles(res.point, 7, 0x8a1414, 2.4, 0.1, 0.4);
    } else if (res.normal) {
      addDecal(res.point, res.normal);
      spawnParticles(res.point, 6, 0xcbb287, 2.2, 0.07, 0.35);
      burst(0.05, 2500, 0.07);
    }
  }
  function startReload() {
    const def = WEAPONS[cur];
    if (def.melee || reloadT > 0) return;
    const am = ammo[cur];
    if (am.mag >= def.mag || am.res <= 0) return;
    reloadT = def.reloadT; sReload();
  }
  function finishReload() {
    const def = WEAPONS[cur];
    const am = ammo[cur];
    const need = def.mag - am.mag;
    const take = Math.min(need, am.res);
    am.mag += take; am.res -= take;
  }
  function switchWeapon(id: WeaponId) {
    if (!owned[id] || (cur === id && !bombOut && !nadeOut)) return;
    bombOut = false;
    cur = id; reloadT = 0; switchT = 0.25; refreshVM(); sDraw(WEAPONS[id].melee);
  }
  function drawBomb() {
    if (!defuse || !attacking || !playerCarrier || bombState !== "carried" || bombOut) return;
    holsterNade();
    bombOut = true; switchT = 0.25;
    refreshVM(); sDraw(true);
  }

  // ============ bot AI ============
  const tmpV = new THREE.Vector3();
  const tmpV2 = new THREE.Vector3();
  const tmpV3 = new THREE.Vector3();
  const LANES: Record<"A" | "mid" | "B", [number, number][]> = MAP === "bazaar" ? {
    A:   [[-20, 8], [-34, 8], [-34, -8], [-20, -19], [-30, -26], [-16, -34], [-12, 16]],
    mid: [[3, 14], [3, 0], [0, -8], [0, -16], [-3, -26], [-12, -2], [12, -2]],
    B:   [[20, 8], [34, 8], [34, -8], [20, -19], [30, -26], [16, -34], [12, 16]],
  } : {
    A:   [[-24, 18], [-24, -10], [-42, -10], [-24, -26], [-28, -40], [-17, -2]],
    mid: [[0, 18], [0, 4], [0, -7], [0, -20], [0, -38], [0, 30]],
    B:   [[24, 18], [24, -10], [42, -10], [24, -26], [28, -40], [17, -2]],
  };
  function laneWp(b: Bot): [number, number] {
    const lane = LANES[b.role];
    return Math.random() < 0.8 ? lane[Math.floor(rand(0, lane.length))] : WPS[Math.floor(rand(0, WPS.length))];
  }
  function findCover(from: THREE.Vector3, threatEye: THREE.Vector3): [number, number] | null {
    let best: [number, number] | null = null, bd = 1e9;
    for (const r of mapRects) {
      if (r.w > 14 || r.d > 14) continue; // skip perimeter walls
      const cands: [number, number][] = [
        [r.x - r.w / 2 - 1.0, r.z], [r.x + r.w / 2 + 1.0, r.z],
        [r.x, r.z - r.d / 2 - 1.0], [r.x, r.z + r.d / 2 + 1.0],
      ];
      for (const c of cands) {
        const d = (c[0] - from.x) ** 2 + (c[1] - from.z) ** 2;
        if (d > 22 * 22 || d >= bd) continue;
        if (Math.abs(c[0]) > 46 || Math.abs(c[1]) > 46) continue;
        if (losClear(new THREE.Vector3(c[0], 1.4, c[1]), threatEye)) continue; // spot doesn't break LOS
        bd = d; best = c;
      }
    }
    return best;
  }
  function botObjective(b: Bot): [number, number] | null {
    return defuseObjective(b);
  }
  function acquireTarget(b: Bot): BotTarget | null {
    let best: BotTarget | null = null, bd = 1e9;
    const bp = b.g.position;
    // solo: only the opposite team is hostile. mp (DM): everyone is hostile.
    if ((mp || b.team === "red") && P.alive) {
      const d = bp.distanceToSquared(P.pos);
      if (d < bd) { bd = d; best = { pos: P.pos, eyeY: P.pos.y + (P.crouching ? 1.1 : 1.62), kind: "player" }; }
    }
    if (mp) {
      peers.forEach((p) => {
        if (!p.alive) return;
        const d = bp.distanceToSquared(p.g.position);
        if (d < bd) { bd = d; best = { pos: p.g.position, eyeY: p.g.position.y + 1.62, kind: "peer", peer: p }; }
      });
      if (isHost) for (const ob of bots) {
        if (ob === b || !ob.alive) continue;
        const d = bp.distanceToSquared(ob.g.position);
        if (d < bd) { bd = d; best = { pos: ob.g.position, eyeY: ob.g.position.y + 1.62, kind: "bot", bot: ob }; }
      }
    } else {
      for (const ob of bots) {
        if (ob === b || !ob.alive || ob.team === b.team) continue;
        const d = bp.distanceToSquared(ob.g.position);
        if (d < bd) { bd = d; best = { pos: ob.g.position, eyeY: ob.g.position.y + 1.62, kind: "bot", bot: ob }; }
      }
    }
    return best;
  }
  function botUpdate(b: Bot, dt: number) {
    if (!b.alive) {
      if (b.g.visible && b.vis.mixer) animChar(b.vis, dt, 0, false, false);
      if (mp && isHost) {
        b.respawnT -= dt;
        if (b.respawnT <= 0) {
          const sp = randomSpawn();
          b.g.position.set(sp[0], 0, sp[1]);
          b.hp = 100; b.alive = true; b.g.visible = true;
          charRevive(b.vis);
          b.path = []; b.pathI = 0; b.repathT = 0; b.seeT = 0;
          b.burstLeft = 0; b.burstCd = rand(0.4, 1.0);
        }
      }
      return;
    }
    const bp = b.g.position;
    if (b.flinchT > 0) b.flinchT -= dt;
    const eye = tmpV.set(bp.x, bp.y + 1.62, bp.z);
    const target = acquireTarget(b);
    const targetEye = target ? new THREE.Vector3(target.pos.x, target.eyeY, target.pos.z) : null;
    const distToTarget = target ? bp.distanceTo(target.pos) : 1e9;
    const canSee = !!target && distToTarget < 60 && !!targetEye && losClear(eye.clone(), targetEye) && !smokeBlocksLOS(eye, targetEye);

    if (canSee) b.seeT = Math.min(b.seeT + dt, 2); else b.seeT = Math.max(b.seeT - dt * 2, 0);

    const targetYaw = canSee && target
      ? Math.atan2(target.pos.x - bp.x, target.pos.z - bp.z)
      : b.holdT > 0 && b.coverT <= 0
        ? b.holdYaw
        : b.path.length > b.pathI
          ? Math.atan2(WPS[b.path[b.pathI]][0] - bp.x, WPS[b.path[b.pathI]][1] - bp.z)
          : b.yaw;
    let dy = targetYaw - b.yaw;
    while (dy > Math.PI) dy -= Math.PI * 2;
    while (dy < -Math.PI) dy += Math.PI * 2;
    b.yaw += clamp(dy, -DIFF.turn * dt, DIFF.turn * dt);
    b.g.rotation.y = b.yaw;

    let mvx = 0, mvz = 0;
    const followPath = (): boolean => {
      while (b.pathI < b.path.length) {
        const wp = WPS[b.path[b.pathI]];
        const dx = wp[0] - bp.x, dz = wp[1] - bp.z;
        const d = Math.hypot(dx, dz);
        if (d < 1.1) { b.pathI++; continue; }
        mvx = dx / d; mvz = dz / d; return true;
      }
      if (b.hasGoal) {
        const dx = b.gx - bp.x, dz = b.gz - bp.z;
        const d = Math.hypot(dx, dz);
        if (d > 0.9) { mvx = dx / d; mvz = dz / d; return true; }
        b.hasGoal = false;
      }
      return false;
    };
    const busyOnBomb = defuse && botBombAction(b, canSee, dt);
    const inCombat = canSee && distToTarget < 26 && b.coverT <= 0 && !busyOnBomb;
    const speed = b.coverT > 0 ? 5.4 : inCombat ? 3.2 : 4.8;
    if (b.coverCd > 0) b.coverCd -= dt;
    // low HP under fire -> sprint to a LOS-breaking spot, sometimes covering with smoke
    if (canSee && target && targetEye && b.hp < DIFF.coverHp && b.coverT <= 0 && b.coverCd <= 0 && !busyOnBomb) {
      const spot = findCover(bp, targetEye);
      b.coverCd = rand(7, 12);
      if (spot) {
        b.path = findPath(bp.x, bp.z, spot[0], spot[1]);
        b.pathI = 0; b.gx = spot[0]; b.gz = spot[1]; b.hasGoal = true;
        b.coverT = rand(1.8, 2.8); b.holdT = 0;
        if (!mp && Math.random() < DIFF.nadeChance) {
          const sd = targetEye.clone().sub(eye).normalize(); sd.y += 0.4; sd.normalize();
          throwNade("smoke", eye.clone().addScaledVector(sd, 0.7), sd, false, b.team);
        }
      }
    }
    if (busyOnBomb) {
      // planting / defusing: stand still
      b.holdT = 0; b.coverT = 0;
    } else if (b.coverT > 0) {
      b.coverT -= dt;
      if (!followPath()) b.coverT = 0; // arrived behind cover
    } else if (inCombat && target) {
      b.strafeT -= dt;
      if (b.strafeT <= 0) { b.strafeDir = Math.random() < 0.5 ? -1 : 1; b.strafeT = rand(0.5, 1.3); }
      const toP = new THREE.Vector3(target.pos.x - bp.x, 0, target.pos.z - bp.z).normalize();
      const side = new THREE.Vector3(-toP.z, 0, toP.x).multiplyScalar(b.strafeDir);
      const closeFactor = distToTarget > 17 ? 0.7 : distToTarget < 8 ? -0.45 : 0;
      mvx = side.x + toP.x * closeFactor; mvz = side.z + toP.z * closeFactor;
    } else {
      b.repathT -= dt;
      if (b.holdT > 0) {
        b.holdT -= dt; // holding an angle: stand still, face holdYaw
      } else if (b.path.length === 0 || (b.pathI >= b.path.length && !b.hasGoal) || b.repathT <= 0) {
        const arrived = b.path.length > 0 && b.pathI >= b.path.length && !b.hasGoal;
        if (arrived && !canSee && Math.random() < 0.45) {
          // settle into an angle hold facing the likely approach
          b.holdT = rand(2, 5);
          b.holdYaw = Math.atan2(-bp.x * 0.4 + rand(-6, 6), 30 - bp.z);
        } else {
          const obj = botObjective(b);
          const roam: [number, number] = obj ?? (target && Math.random() < (mp ? 0.55 : 0.6)
            ? [target.pos.x, target.pos.z]
            : laneWp(b));
          b.path = findPath(bp.x, bp.z, roam[0], roam[1]);
          b.pathI = 0; b.gx = roam[0]; b.gz = roam[1]; b.hasGoal = true;
          b.repathT = rand(2.5, 4.5);
        }
      }
      followPath();
    }
    // unstick: if trying to move but barely displacing, sidestep then repath
    if (b.sideT > 0) {
      b.sideT -= dt;
      const px2 = -mvz, pz2 = mvx; // perpendicular
      const pl = Math.hypot(px2, pz2) || 1;
      mvx = (mvx * 0.3 + (px2 / pl) * b.sideDir) ; mvz = (mvz * 0.3 + (pz2 / pl) * b.sideDir);
    }
    const ml = Math.hypot(mvx, mvz);
    let strafeLean = 0;
    if (ml > 0.01) {
      const wantD = speed * dt;
      bp.x += (mvx / ml) * speed * dt;
      bp.z += (mvz / ml) * speed * dt;
      collide(bp, 1.8);
      const gotD = Math.hypot(bp.x - b.lastPX, bp.z - b.lastPZ);
      if (gotD < wantD * 0.35) {
        b.stuckT += dt;
        if (b.stuckT > 0.5 && b.sideT <= 0) { b.sideDir = Math.random() < 0.5 ? -1 : 1; b.sideT = rand(0.35, 0.6); }
        if (b.stuckT > 1.4) {
          // hard repath: skip current waypoint, pick a fresh lane goal
          b.pathI++;
          if (b.pathI >= b.path.length) {
            const roam = laneWp(b);
            b.path = findPath(bp.x, bp.z, roam[0], roam[1]);
            b.pathI = 0; b.gx = roam[0]; b.gz = roam[1]; b.hasGoal = true;
          }
          b.repathT = rand(2.5, 4.5);
          b.stuckT = 0; b.holdT = 0; b.coverT = 0;
        }
      } else b.stuckT = Math.max(0, b.stuckT - dt * 2);
      const rx = Math.cos(b.yaw), rz = -Math.sin(b.yaw);
      strafeLean = clamp((mvx / ml) * rx + (mvz / ml) * rz, -1, 1) * -0.09;
      b.stepAcc += speed * dt;
      if (b.stepAcc > 2.8) {
        b.stepAcc = 0;
        const pv = panVol(bp);
        const enemyStep = mp || b.team === "red";
        if (pv.mul > (enemyStep ? 0.08 : 0.25)) {
          if (enemyStep) {
            // enemy steps carry: louder, and muffled (not silent) through walls
            const occluded = !losClear(tmpV2.set(camera.position.x, camera.position.y, camera.position.z), tmpV3.set(bp.x, bp.y + 1.2, bp.z));
            sStep(0.16 * pv.mul, pv.pan, occluded);
          } else {
            sStep(0.05 * pv.mul, pv.pan); // teammates: soft presence
          }
        }
      }
    }
    b.lastPX = bp.x; b.lastPZ = bp.z;
    bp.y = groundHeightAt(bp.x, bp.z);
    b.shootT -= dt;
    animChar(b.vis, dt, ml > 0.01 ? speed : 0, b.shootT > 0, canSee && b.seeT > 0.2);

    b.fireT -= dt;
    b.burstCd -= dt;
    b.nadeCd -= dt;
    if (canSee) { if (!b.wasSee) b.reactT = DIFF.react * rand(0.7, 1.5); b.wasSee = true; } else b.wasSee = false;
    if (b.reactT > 0) b.reactT -= dt;
    // mid-range frag toss (solo modes)
    if (!mp && canSee && target && targetEye && b.nadeCd <= 0 && b.reactT <= 0 && distToTarget > 9 && distToTarget < 30 && !busyOnBomb) {
      b.nadeCd = rand(12, 22);
      if (Math.random() < DIFF.nadeChance) {
        const nd = targetEye.clone().sub(eye).normalize();
        nd.y += 0.32 + distToTarget * 0.01; nd.normalize();
        throwNade("frag", eye.clone().addScaledVector(nd, 0.7), nd, false, b.team);
        b.shootT = 0.5;
      }
    }
    if (canSee && b.seeT > 0.1 && b.reactT <= 0 && target && targetEye && !busyOnBomb) {
      b.gun.rotation.x = -Math.atan2(targetEye.y - (bp.y + 1.3), distToTarget) * 0.5;
      if (b.burstLeft > 0 && b.fireT <= 0) {
        b.burstLeft--;
        b.fireT = 0.095;
        b.shootT = 0.45;
        const muzzle = eye.clone().add(new THREE.Vector3(Math.sin(b.yaw), -0.25, Math.cos(b.yaw)).multiplyScalar(0.5));
        const aim = targetEye.clone().sub(muzzle).normalize();
        const err = clamp(0.05 - b.seeT * 0.02, 0.012, 0.05) * DIFF.aimErr * (distToTarget * 0.09 + 1) * (ml > 0.01 ? 1.4 : 1);
        aim.x += rand(-err, err); aim.y += rand(-err, err); aim.z += rand(-err, err);
        aim.normalize();
        const pv = panVol(muzzle);
        sShot("rifle", clamp(pv.mul, 0.08, 0.8), pv.pan);
        muzzleFlashAt(muzzle, true);
        if (mp && isHost) bcSend({ t: "shot", fx: muzzle.x, fy: muzzle.y, fz: muzzle.z, ex: muzzle.x + aim.x * distToTarget, ey: muzzle.y + aim.y * distToTarget, ez: muzzle.z + aim.z * distToTarget, w: "rifle" });
        ray.set(muzzle, aim); ray.far = distToTarget + 2;
        const wallHits = ray.intersectObjects(staticGroup.children, false);
        const wallD = wallHits.length ? wallHits[0].distance : Infinity;
        const tCenter = new THREE.Vector3(target.pos.x, target.pos.y + 1.1, target.pos.z);
        const toC = tCenter.clone().sub(muzzle);
        const proj = toC.dot(aim);
        let hit = false;
        if (proj > 0 && proj < wallD) {
          const closest = muzzle.clone().addScaledVector(aim, proj);
          hit = closest.distanceTo(tCenter) < 0.55;
        }
        const end = muzzle.clone().addScaledVector(aim, Math.min(wallD, distToTarget + 2));
        addTracer(muzzle, end, 0xffb38a, 0.08);
        if (!hit && target.kind === "player" && proj > 0 && proj < wallD) {
          const closest2 = muzzle.clone().addScaledVector(aim, proj);
          const missD = closest2.distanceTo(new THREE.Vector3(P.pos.x, P.pos.y + 1.1, P.pos.z));
          if (missD < 2) burst(0.07, 5200, 0.16, 0, rand(-0.6, 0.6)); // bullet whizz
        }
        if (hit) {
          const dmg = Math.round(rand(11, 24));
          if (target.kind === "player") {
            damagePlayer(dmg, b.name, b.netId, "AK-103", false, bp.clone());
          } else if (target.kind === "peer" && target.peer) {
            bcSend({ t: "hit", tgt: target.peer.id, dmg, fn: b.name, w: "AK-103", h: false });
          } else if (target.kind === "bot" && target.bot) {
            hostDamageBot(target.bot, dmg, b.name, "", b);
          }
        } else if (wallHits.length && wallHits[0].face) {
          const n = wallHits[0].face.normal.clone().transformDirection(wallHits[0].object.matrixWorld);
          addDecal(wallHits[0].point, n);
        }
      }
      if (b.burstLeft <= 0 && b.burstCd <= 0) {
        b.burstLeft = Math.floor(rand(DIFF.burstA, DIFF.burstB + 1));
        b.burstCd = rand(0.5, 1.1) + (distToTarget > 30 ? 0.4 : 0);
      }
    } else {
      b.gun.rotation.x = damp(b.gun.rotation.x, 0, 9, dt);
    }
  }
  function hostDamageBot(b: Bot, dmg: number, fromName: string, shooterId = "", shooterBot?: Bot) {
    if (!b.alive) return;
    b.vis.hitT = 0.45;
    b.hp -= dmg;
    b.flinchT = 0.16;
    if (b.hp <= 0) {
      botDie(b, fromName, false, new THREE.Vector3(rand(-1, 1), 1.3, rand(-1, 1)).normalize().multiplyScalar(3.4));
      if (shooterBot) { shooterBot.kills++; roundKillsBot.set(shooterBot.name, (roundKillsBot.get(shooterBot.name) || 0) + 1); }
      killFeed(fromName, b.name, "AK-103", false);
      if (mp) bcSend({ t: "death", killer: shooterId, kn: fromName, vn: b.name, w: "AK-103", h: false });
      checkRoundEnd();
    }
  }
  function botDie(b: Bot, _killer: string, isHead: boolean, impulse: THREE.Vector3) {
    void impulse; void isHead;
    b.deaths++;
    if (defuse && b.carrier) { b.carrier = false; dropBomb(b.g.position.x, b.g.position.z); }
    if (Math.random() < 0.5) spawnDrop("rifle", b.g.position.x + rand(-0.5, 0.5), b.g.position.z + rand(-0.5, 0.5));
    b.alive = false; b.deadT = 0; b.deadDir = rand(-1, 1);
    b.respawnT = mp ? rand(2.5, 4) : 1e9;
    charDie(b.vis);
    if (mp && isHost) bcSend({ t: "bs", bid: b.netId, x: b.g.position.x, z: b.g.position.z, yaw: b.yaw, a: false, n: b.name, rag: true, ix: impulse.x, iy: impulse.y, iz: impulse.z });
  }

  // ============ defuse: C4 object + helpers ============
  const bombG = new THREE.Group();
  {
    const body = new THREE.Mesh(new THREE.BoxGeometry(0.34, 0.12, 0.22), new THREE.MeshStandardMaterial({ color: 0x3a3a32, roughness: 0.6, metalness: 0.3 }));
    bombG.add(body);
    const led = new THREE.Mesh(new THREE.BoxGeometry(0.05, 0.03, 0.05), new THREE.MeshStandardMaterial({ color: 0xff2222, emissive: 0xff0000, emissiveIntensity: 2 }));
    led.position.set(0.09, 0.075, 0);
    bombG.add(led);
    bombG.userData.led = led;
    bombG.visible = false;
    scene.add(bombG);
  }
  function siteAt(x: number, z: number): "A" | "B" | null {
    for (const k of ["A", "B"] as const) {
      const st = SITES[k];
      if ((x - st.x) ** 2 + (z - st.z) ** 2 < st.r * st.r) return k;
    }
    return null;
  }
  function dropBomb(x: number, z: number) {
    bombState = "dropped"; bombX = x; bombZ = z;
    bombG.visible = true;
    bombG.position.set(x, groundHeightAt(x, z) + 0.09, z);
    bombG.rotation.set(0, rand(0, 6.28), 0);
    centerMsg("BOMB DROPPED", 1.6, "#ffd76e");
  }
  function plantBomb(x: number, z: number, who: string) {
    bombState = "planted"; bombT = BOMB_LEN;
    bombX = x; bombZ = z; bombSite = siteAt(x, z) || "A";
    bombG.visible = true;
    bombG.position.set(x, groundHeightAt(x, z) + 0.09, z);
    centerMsg(who + " PLANTED THE BOMB — " + bombSite, 2.4, "#ff9d7a");
    sBuy();
    phaseT = Math.max(phaseT, BOMB_LEN + 2); // round timer no longer ends it; bomb does
  }
  function explodeBomb() {
    bombState = "exploded";
    bombG.visible = false;
    const pos = new THREE.Vector3(bombX, groundHeightAt(bombX, bombZ) + 0.4, bombZ);
    spawnParticles(pos, 60, 0xffb056, 14, 0.3, 0.8, 9);
    spawnParticles(pos, 40, 0x55504a, 9, 0.5, 1.4, 4);
    flashLight.position.copy(pos); flashLight.intensity = 30;
    burst(1.0, 500, 1.4); blip(45, 0.6, 1.0, "sawtooth"); burst(1.8, 240, 0.5, 0.15);
    addTrauma(1.0); rumble(1, 1, 600);
    const R = 16;
    if (P.alive && P.pos.distanceTo(pos) < R) damagePlayer(200, "THE BOMB", undefined, "C4", false, pos.clone());
    for (const b of bots) if (b.alive && b.g.position.distanceTo(pos) < R) { b.hp = 0; botDie(b, "C4", false, b.g.position.clone().sub(pos).setY(2).normalize().multiplyScalar(6)); }
    endRound(!attacking ? false : true, "TARGET DESTROYED");
  }
  function defuseDone(who: string) {
    bombState = "defused";
    sWin();
    endRound(attacking ? false : true, who + " DEFUSED — " + Math.max(0, bombT).toFixed(1) + "s LEFT");
  }
  // bot bomb behaviors: returns true when bot is busy planting/defusing
  function botBombAction(b: Bot, canSee: boolean, dt: number): boolean {
    if (!defuse || phase !== "live") return false;
    const bp = b.g.position;
    const botAttacks = b.team === "red" ? !attacking : attacking;
    if (botAttacks) {
      // BOTS attack: carrier plants
      if (b.carrier && bombState === "carried") {
        const st = SITES[b.defSite];
        if ((bp.x - st.x) ** 2 + (bp.z - st.z) ** 2 < (st.r - 1.5) ** 2) {
          b.plantT += dt;
          if (b.plantT > 0.4 && beepAcc <= 0) { blip(880, 0.05, 0.12, "square"); beepAcc = 0.5; }
          if (b.plantT >= PLANT_LEN) { b.carrier = false; plantBomb(bp.x, bp.z, b.name); }
          return true;
        }
        b.plantT = 0;
      }
      // dropped bomb: nearest attacker bot picks it up
      if (bombState === "dropped" && !canSee) {
        const d2 = (bp.x - bombX) ** 2 + (bp.z - bombZ) ** 2;
        if (d2 < 1.6) {
          bombState = "carried"; b.carrier = true; bombG.visible = false;
          centerMsg(b.name + " HAS THE BOMB", 1.6, "#ff9d7a");
        }
      }
    } else {
      // defending bots: defuse planted bomb if no enemy visible
      if (bombState === "planted") {
        const d2 = (bp.x - bombX) ** 2 + (bp.z - bombZ) ** 2;
        if (d2 < 2.2 && !canSee) {
          b.defuseT += dt;
          if (b.defuseT >= DEFUSE_LEN) defuseDone(b.name);
          return true;
        }
        b.defuseT = Math.max(0, b.defuseT - dt * 2);
      }
    }
    return false;
  }
  // navigation objective for bots in defuse mode
  function defuseObjective(b: Bot): [number, number] | null {
    if (!defuse || phase !== "live") return null;
    const botAttacks = b.team === "red" ? !attacking : attacking;
    if (botAttacks) {
      if (b.carrier && bombState === "carried") { const st = SITES[b.defSite]; return [st.x + rand(-3, 3), st.z + rand(-3, 3)]; }
      if (bombState === "dropped") return [bombX, bombZ];
      if (bombState === "planted") { if (Math.random() < 0.65) return [bombX + rand(-8, 8), bombZ + rand(-8, 8)]; return null; }
      const carrier = bots.find((x) => x.carrier && x.alive && x.team === b.team);
      if (carrier && Math.random() < 0.5) { const st = SITES[carrier.defSite]; return [st.x + rand(-6, 6), st.z + rand(-6, 6)]; }
      if (b.team === "blue" && playerCarrier && Math.random() < 0.6) return [P.pos.x + rand(-7, 7), P.pos.z + rand(-7, 7)];
      return null;
    }
    if (bombState === "planted") return [bombX + rand(-5, 5), bombZ + rand(-5, 5)];
    if (Math.random() < 0.7) { const st = Math.random() < 0.5 ? SITES[b.defSite] : SITES[b.defSite === "A" ? "B" : "A"]; return [st.x + rand(-5, 5), st.z + rand(-5, 5)]; }
    return null;
  }

  // ============ rounds ============
  function resetPositions() {
    const mirror = defuse && !attacking; // defending half: your squad holds the north (site side)
    const pSp: [number, number] = mirror ? [0, -44] : PLAYER_SPAWN;
    P.pos.set(pSp[0], 0, pSp[1]);
    P.vel.set(0, 0, 0);
    P.yaw = mirror ? Math.PI : 0; P.pitch = 0; // face map center
    P.hp = 100; P.alive = true;
    elRespawn.style.display = "none";
    elXhair.style.display = "block";
    vmRoot.visible = true; deathCamT = 0; specIdx = -1;
    elDeathFade.style.opacity = "0";
    let ri = 0, bi = 0;
    bots.forEach((b, i) => {
      const northSide = mirror ? b.team === "blue" : b.team === "red";
      const arr = b.team === "red" ? ENEMY_SPAWNS : FRIEND_SPAWNS;
      const sp = arr[(b.team === "red" ? ri++ : bi++) % arr.length];
      b.g.position.set(sp[0] + rand(-1, 1), 0, northSide ? -Math.abs(sp[1]) : Math.abs(sp[1]));
      b.g.rotation.x = 0;
      b.yaw = northSide ? 0 : Math.PI;
      b.hp = 100; b.alive = true; b.g.visible = true;
      charRevive(b.vis);
      b.path = []; b.pathI = 0; b.repathT = rand(0, 1); b.seeT = 0;
      b.burstLeft = 0; b.burstCd = rand(0.5, 1.5); b.deadT = 0;
      b.g.rotation.y = b.yaw;
      b.role = (["A", "mid", "B", "mid", "A"] as const)[i % 5];
      b.reactT = 0; b.wasSee = false; b.nadeCd = rand(8, 16); b.coverT = 0; b.coverCd = 0;
      b.holdT = 0; b.hasGoal = false; b.plantT = 0; b.defuseT = 0; b.carrier = false;
    });
  }
  function startRound() {
    round++;
    if (defuse && round === HALF_ROUNDS + 1) {
      attacking = !attacking;
      P.money = 2400; P.armor = 0; hasKit = false;
      centerMsg("SIDE SWITCH — YOU NOW " + (attacking ? "ATTACK" : "DEFEND"), 3, "#ffd76e");
    }
    phase = "buy"; phaseT = BUY_TIME;
    resetPositions();
    (Object.keys(WEAPONS) as WeaponId[]).forEach((w) => {
      if (owned[w] && !WEAPONS[w].melee) ammo[w] = { mag: WEAPONS[w].mag, res: WEAPONS[w].reserve };
    });
    if (owned.rifle) cur = "rifle"; else if (owned.sniper) cur = "sniper"; else if (owned.smg) cur = "smg"; else cur = "pistol";
    frags = Math.max(frags, 1); smokes = Math.max(smokes, 1);
    reloadT = 0; refreshVM();
    if (defuse) {
      bombState = attacking ? "carried" : "carried";
      bombG.visible = false;
      playerPlantT = 0; playerDefuseT = 0; beepAcc = 0;
      bombOut = false; refreshVM();
      // defenders may BUY a kit ($400); not free anymore
      if (attacking) {
        playerCarrier = true;
        centerMsg("ROUND " + round + " — PLANT AT A OR B", 2.6, "#ffd76e");
      } else {
        playerCarrier = false;
        const reds = bots.filter((bb) => bb.team === "red");
        const carrier = reds[Math.floor(rand(0, reds.length))];
        bots.forEach((bb) => { bb.carrier = bb === carrier; bb.defSite = Math.random() < 0.5 ? "A" : "B"; });
        // attackers group toward carrier's chosen site
        centerMsg("ROUND " + round + " — DEFEND THE SITES", 2.6, "#7fb3ff");
      }
    } else centerMsg("ROUND " + round, 2, "#ffd76e");
    updateBuyMenu();
  }
  function endRound(playerWon: boolean, reason: string) {
    if (phase === "end") return;
    phase = "end"; phaseT = END_TIME;
    if (playerWon) {
      myScore++; lossStreak = 0;
      P.money = Math.min(9000, P.money + 1900);
      sWin(); centerMsg("ROUND WON — " + reason, 3, "#9fe870");
    } else {
      enemyScore++; lossStreak = Math.min(4, lossStreak + 1);
      P.money = Math.min(9000, P.money + 1100 + lossStreak * 250); // CS-style loss-streak bonus
      sLose(); centerMsg("ROUND LOST — " + reason, 3, "#ff9d7a");
    }
    // round MVP: most kills this round (you + every bot)
    let mvpName = roundKillsMe > 0 ? "YOU" : "", mvpK = roundKillsMe;
    roundKillsBot.forEach((k, n) => { if (k > mvpK) { mvpK = k; mvpName = n; } });
    if (mvpK >= 2 && mvpName) {
      const isBlue = isBlueName(mvpName);
      setTimeout(() => { if (phase === "end") centerMsg("MVP: " + mvpName + " — " + mvpK + " KILLS", 2.2, isBlue ? "#7fb3ff" : "#ff9d7a"); }, 1700);
    }
    // halftime callout (defuse)
    if (defuse && round === HALF_ROUNDS && myScore < ROUND_TARGET && enemyScore < ROUND_TARGET) {
      setTimeout(() => { if (phase === "end") centerMsg("HALFTIME — SWITCHING SIDES", 2.4, "#ffd76e"); }, 2400);
    }
    roundKillsMe = 0; roundKillsBot.clear();
    elScoreMe.textContent = String(myScore);
    elScoreEn.textContent = String(enemyScore);
    if (buyOpen) { buyOpen = false; elBuy.style.display = "none"; }
  }
  function checkRoundEnd() {
    if (mp) return;
    if (phase !== "live" && phase !== "buy") return;
    const blueAlive = P.alive || bots.some((b) => b.team === "blue" && b.alive);
    const redAlive = bots.some((b) => b.team === "red" && b.alive);
    if (defuse) {
      if (!blueAlive) {
        if (attacking && bombState === "planted") return;      // your plant can still win
        if (!attacking && bombState === "planted") { explodeBomb(); return; } // nobody left to defuse
        endRound(false, "SQUAD ELIMINATED");
      } else if (!redAlive) {
        if (!attacking && bombState === "planted") return;     // bomb ticking — must be defused first
        endRound(true, "ENEMIES ELIMINATED");
      }
      return;
    }
    if (!blueAlive) endRound(false, "SQUAD ELIMINATED");
    else if (!redAlive) endRound(true, "ENEMIES ELIMINATED");
  }

  function drawNade(kind: "frag" | "smoke") {
    const have = kind === "frag" ? frags : smokes;
    if (have <= 0) { sClick(); return; }
    if (nadeOut && nadeSel === kind) return;
    bombOut = false;
    if (!nadeOut) lastGunWeapon = cur === "knife" ? lastGunWeapon : cur;
    nadeSel = kind; nadeOut = true; nadeDrawT = 0.22;
    ads = false;
    refreshVM();
    sClick();
  }
  function holsterNade() {
    if (!nadeOut) return;
    nadeOut = false; nadeDrawT = 0;
    refreshVM();
  }
  // ============ input ============
  document.addEventListener("keydown", (e) => {
    if (["KeyW", "KeyA", "KeyS", "KeyD", "Space", "Tab"].includes(e.code)) e.preventDefault();
    keys.add(e.code);
    if (e.code === "Tab") { boardOpen = true; updateBoard(); elBoard.style.display = "block"; }
    if (e.code === "KeyR") { startReload(); inspectT = 0; }
    if (e.code === "Enter" && mp && warmup) {
      if (isHost) { beginMatch(); bcSend({ t: "begin", lim: dmLimit }); }
      else if (!myReady) { myReady = true; bcSend({ t: "rdy" }); }
    }
    if (e.code === "KeyF" && P.alive && !nadeOut && reloadT <= 0 && inspectT <= 0 && throwAnimT <= 0) inspectT = INSPECT_LEN;
    if (e.code === "KeyG" && P.alive && bombOut) {
      bombOut = false; playerCarrier = false;
      dropBomb(P.pos.x + Math.sin(P.yaw) * 0.8, P.pos.z + Math.cos(P.yaw) * 0.8);
      refreshVM(); switchT = 0.2;
      return;
    }
    if (e.code === "KeyG" && P.alive && !buyOpen && phase !== "end" && !nadeOut) {
      // CS-style: G drops the current weapon on the ground
      if (cur === "rifle" || cur === "smg" || cur === "sniper" || cur === "pistol") {
        const dropped = cur;
        const dir2 = new THREE.Vector3();
        camera.getWorldDirection(dir2);
        const dx = P.pos.x + dir2.x * 1.4, dz = P.pos.z + dir2.z * 1.4;
        spawnDrop(dropped, dx, dz, dropped === "pistol");
        if (mp) bcSend({ t: "drop", w: dropped, x: dx, z: dz });
        owned[dropped] = false;
        cur = dropped !== "pistol" && owned.pistol ? "pistol" : "knife";
        if (lastGunWeapon === dropped) lastGunWeapon = cur;
        reloadT = 0; switchT = 0.3; refreshVM();
        sClick(); rumble(0.15, 0.2, 35);
      } else sClick();
    }
    if ((e.code === "ControlLeft" || e.code === "KeyC") && sprintAmt > 0.5 && P.onGround && slideCd <= 0 && P.alive && !buyOpen) {
      slideT = 0.5; slideCd = 1.4;
      const hv = Math.hypot(P.vel.x, P.vel.z) || 1;
      P.vel.x = (P.vel.x / hv) * 9.5;
      P.vel.z = (P.vel.z / hv) * 9.5;
      burst(0.2, 650, 0.12);
    }
    if (buyOpen) {
      if (e.code === "Digit1") tryBuy("smg");
      if (e.code === "Digit2") tryBuy("rifle");
      if (e.code === "Digit3") tryBuy("sniper");
      if (e.code === "Digit4") tryBuy("armor");
      if (e.code === "Digit5") tryBuy("frag");
      if (e.code === "Digit6") tryBuy("smoke");
      if (e.code === "Digit7") tryBuy("kit");
    } else {
      if (e.code === "Digit1") {
        const prim = (["rifle", "sniper", "smg"] as WeaponId[]).filter((w) => owned[w]);
        if (prim.length) { holsterNade(); switchWeapon(prim[(prim.indexOf(cur) + 1) % prim.length]); }
      }
      if (e.code === "Digit2") { holsterNade(); switchWeapon("pistol"); }
      if (e.code === "Digit3") { holsterNade(); switchWeapon("knife"); }
      if (e.code === "Digit4" && P.alive) {
        // CS-style: 4 cycles through grenades
        if (!nadeOut) drawNade(frags > 0 ? "frag" : "smoke");
        else drawNade(nadeSel === "frag" ? "smoke" : "frag");
      }
      if (e.code === "Digit5" && P.alive) drawBomb();
      if (e.code === "KeyQ" && P.alive) {
        // quick-switch to last gun (CS muscle memory)
        if (bombOut) { bombOut = false; refreshVM(); switchT = 0.2; }
        else if (nadeOut) holsterNade();
        else if (cur !== lastGunWeapon && owned[lastGunWeapon]) switchWeapon(lastGunWeapon);
        else if (!owned[lastGunWeapon]) lastGunWeapon = cur;
      }
    }
    if (e.code === "KeyB" && phase === "buy") {
      buyOpen = !buyOpen;
      elBuy.style.display = buyOpen ? "block" : "none";
      if (buyOpen) { updateBuyMenu(); document.exitPointerLock(); }
      else canvas.requestPointerLock();
    }
  }, sig);
  document.addEventListener("keyup", (e) => {
    keys.delete(e.code);
    if (e.code === "Tab") { boardOpen = false; elBoard.style.display = "none"; }
  }, sig);
  canvas.addEventListener("mousedown", (e) => {
    audioInit();
    if (!locked) { canvas.requestPointerLock(); return; }
    if (!P.alive && deathCamT > 1.2 && e.button === 0) { cycleSpec(); return; }
    if (e.button === 0) { mouseDown = true; semiQueue = true; }
    if (e.button === 2) {
      if (nadeOut) { holsterNade(); switchWeapon(owned[lastGunWeapon] ? lastGunWeapon : "knife"); }
      else ads = true;
    }
  }, sig);
  document.addEventListener("mouseup", (e) => {
    if (e.button === 0) mouseDown = false;
    if (e.button === 2) ads = false;
  }, sig);
  canvas.addEventListener("contextmenu", (e) => e.preventDefault(), sig);
  document.addEventListener("contextmenu", (e) => { if (locked) e.preventDefault(); }, sig);
  document.addEventListener("pointerlockchange", () => {
    locked = document.pointerLockElement === canvas;
  }, sig);
  document.addEventListener("mousemove", (e) => {
    if (!locked || boardOpen) return;
    P.yaw -= e.movementX * 0.0022 * sens;
    P.pitch -= e.movementY * 0.0022 * sens;
    P.pitch = clamp(P.pitch, -1.45, 1.45);
    swayX = clamp(swayX + e.movementX, -70, 70);
    swayY = clamp(swayY + e.movementY, -70, 70);
  }, sig);
  window.addEventListener("resize", () => {
    camera.aspect = container.clientWidth / container.clientHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(container.clientWidth, container.clientHeight);
  }, sig);

  // ============ player tick ============
  function playerTick(dt: number) {
    if (!P.alive) {
      adsAmt = damp(adsAmt, 0, 12, dt);
      sprintAmt = damp(sprintAmt, 0, 8, dt);
      return;
    }
    const fwd = (keys.has("KeyW") ? 1 : 0) - (keys.has("KeyS") ? 1 : 0);
    const strafe = (keys.has("KeyD") ? 1 : 0) - (keys.has("KeyA") ? 1 : 0);
    leanDir = (keys.has("KeyX") ? 1 : 0) - (keys.has("KeyZ") ? 1 : 0);
    const wantCrouch = keys.has("ControlLeft") || keys.has("KeyC");
    if (slideT > 0) slideT -= dt;
    slideCd = Math.max(0, slideCd - dt);
    P.crouching = wantCrouch || slideT > 0;
    const def = WEAPONS[cur];
    const sprinting = keys.has("ShiftLeft") && fwd > 0 && !wantCrouch && !ads && slideT <= 0;
    sprintAmt = damp(sprintAmt, sprinting ? 1 : 0, 9, dt);
    adsAmt = damp(adsAmt, (ads && !def.melee && !nadeOut) ? 1 : 0, 13, dt);
    P.h = damp(P.h, P.crouching ? 1.16 : 1.8, 13, dt);
    const speed = (P.crouching && slideT <= 0 ? 2.9 : sprinting ? 7.8 : 6.0) * (def.melee ? 1.1 : 1) * (1 - adsAmt * 0.45);
    const sin = Math.sin(P.yaw), cos = Math.cos(P.yaw);
    const wishX = (-sin * fwd + cos * strafe);
    const wishZ = (-cos * fwd - sin * strafe);
    const wl = Math.hypot(wishX, wishZ) || 1;
    const accel = slideT > 0 ? 1.4 : P.onGround ? 16 : 3;
    P.vel.x = damp(P.vel.x, (wishX / wl) * speed, accel, dt);
    P.vel.z = damp(P.vel.z, (wishZ / wl) * speed, accel, dt);
    if (keys.has("Space") && P.onGround) { P.vel.y = 5.0; P.onGround = false; burst(0.05, 800, 0.06); }
    P.vel.y -= 14.5 * dt;
    P.pos.x += P.vel.x * dt;
    P.pos.z += P.vel.z * dt;
    collide(P.pos, P.h);
    P.pos.y += P.vel.y * dt;
    const gh = groundHeightAt(P.pos.x, P.pos.z);
    if (P.pos.y <= gh) {
      if (!P.onGround && P.vel.y < -7) { landDip = clamp(-P.vel.y * 0.015, 0, 0.17); burst(0.07, 500, 0.1); addTrauma(0.15); }
      P.pos.y = gh; P.vel.y = 0; P.onGround = true;
    } else P.onGround = false;

    const horSpeed = Math.hypot(P.vel.x, P.vel.z);
    if (P.onGround && horSpeed > 1.5 && slideT <= 0) {
      stepAcc += horSpeed * dt;
      if (stepAcc > 2.4) { stepAcc = 0; sStep(); stepPunch = 0.045; }
    }
    if (P.onGround && horSpeed > 0.5) bobT += dt * horSpeed * 1.5;

    fireT -= dt; switchT -= dt;
    if (reloadT > 0) {
      reloadT -= dt;
      if (reloadT <= 0) finishReload();
    }
    if (nadeOut) {
      nadeDrawT = Math.max(0, nadeDrawT - dt);
      if (semiQueue && mouseDown && throwAnimT <= 0 && nadeDrawT <= 0 && locked && !buyOpen && phase !== "end") {
        semiQueue = false;
        const have = nadeSel === "frag" ? frags : smokes;
        if (have > 0) {
          if (nadeSel === "frag") frags--; else smokes--;
          throwAnimT = 0.34;
          const dir = new THREE.Vector3();
          camera.getWorldDirection(dir);
          const from = camera.getWorldPosition(new THREE.Vector3()).addScaledVector(dir, 0.4);
          const kindThrown = nadeSel;
          setTimeout(() => throwNade(kindThrown, from, dir, true), 130);
          burst(0.06, 900, 0.1);
          rumble(0.2, 0.3, 40);
          // auto-holster after throw if none left
          setTimeout(() => {
            const left = kindThrown === "frag" ? frags : smokes;
            if (left <= 0) { holsterNade(); switchWeapon(owned[lastGunWeapon] ? lastGunWeapon : "knife"); }
          }, 420);
        }
      }
    } else {
      const wantFire = !bombOut && (def.auto ? mouseDown : (semiQueue && mouseDown));
      if (wantFire && fireT <= 0 && reloadT <= 0 && switchT <= 0 && locked && !buyOpen && phase !== "end" && sprintAmt < 0.5) {
        playerShoot();
        if (def.melee) knifeT = 0.25;
        semiQueue = false;
      }
    }
    recoilHeat = Math.max(0, recoilHeat - dt * 6.5);
  }

  // ============ camera + viewmodel ============
  let deathCamT = 0, deathRollDir = 1;
  let specIdx = -1; // -1 = death cam / own corpse; >=0 = index into spectate targets
  function specTargets(): { name: string; pos: THREE.Vector3; yaw: number }[] {
    const out: { name: string; pos: THREE.Vector3; yaw: number }[] = [];
    if (mp) peers.forEach((p) => { if (p.alive) out.push({ name: p.name, pos: p.g.position, yaw: p.g.rotation.y }); });
    for (const b of bots) if (b.alive && b.g.visible && (mp || b.team === "blue")) out.push({ name: b.name, pos: b.g.position, yaw: b.yaw });
    return out;
  }
  function cycleSpec() {
    const t = specTargets();
    if (t.length === 0) { specIdx = -1; return; }
    specIdx = (specIdx + 1) % t.length;
    centerMsg("SPECTATING " + t[specIdx].name, 1.2, "#9aa3ad");
  }
  function cameraTick(dt: number) {
    if (!P.alive) {
      updateNadeArc(false);
      deathCamT += dt;
      const t = specIdx >= 0 ? specTargets() : [];
      if (specIdx >= 0 && t.length > 0) {
        // third-person follow cam on the spectated combatant
        const tgt = t[Math.min(specIdx, t.length - 1)];
        const back = new THREE.Vector3(Math.sin(tgt.yaw), 0, Math.cos(tgt.yaw)).multiplyScalar(-3.4);
        const want = tgt.pos.clone().add(back).setY(tgt.pos.y + 2.2);
        camera.position.lerp(want, 1 - Math.exp(-6 * dt));
        camera.lookAt(tgt.pos.x, tgt.pos.y + 1.3, tgt.pos.z);
      } else {
        // death cam: fall to the ground with a body roll
        const k = 1 - Math.exp(-deathCamT * 3.4);
        const standY = P.pos.y + P.h - 0.18;
        const groundY = P.pos.y + 0.34;
        camera.position.set(P.pos.x, standY + (groundY - standY) * k, P.pos.z);
        camera.rotation.set(P.pitch * (1 - k) - 0.1 * k, P.yaw + 0.35 * k * deathRollDir, 0.95 * k * deathRollDir);
      }
      if (Math.abs(camera.fov - BASE_FOV) > 0.05) { camera.fov = damp(camera.fov, BASE_FOV, 8, dt); camera.updateProjectionMatrix(); }
      return;
    }
    updateNadeArc(nadeOut && throwAnimT <= 0 && P.alive);
    const def = WEAPONS[cur];
    const kick = gunKick * gunKick;
    const fovKick = kick * (cur === "sniper" ? 2.6 : 1.1) * (1 - adsAmt * 0.6);
    const targetFov = (adsAmt > 0.02 && !def.melee
      ? BASE_FOV + (def.ads - BASE_FOV) * adsAmt
      : BASE_FOV + sprintAmt * 7 + (slideT > 0 ? 4 : 0)) + fovKick;
    if (Math.abs(camera.fov - targetFov) > 0.02) {
      camera.fov = damp(camera.fov, targetFov, 14, dt);
      camera.updateProjectionMatrix();
    }
    landDip = Math.max(0, landDip - dt * 0.65);
    // trauma shake (shake = trauma^2, noise-driven, framerate-independent decay)
    trauma = Math.max(0, trauma - dt * 1.4);
    const shake = trauma * trauma * (1 - adsAmt * 0.5);
    // aim punch springs back
    aimPunchP = damp(aimPunchP, 0, 16, dt);
    aimPunchY = damp(aimPunchY, 0, 16, dt);
    shadowProxy.position.set(P.pos.x, P.pos.y, P.pos.z);
    shadowProxy.rotation.y = P.yaw;
    shadowProxy.scale.y = P.h / 1.8;
    shadowProxy.visible = P.alive;
    leanAmt = damp(leanAmt, leanDir * (1 - sprintAmt), 11, dt);
    const leanOff = leanAmt * 0.55;
    const leanRight = tmpV2.set(Math.cos(P.yaw), 0, -Math.sin(P.yaw)); // camera-right in world
    camera.position.set(
      P.pos.x + leanRight.x * leanOff,
      P.pos.y + P.h - 0.18 - landDip - Math.abs(leanAmt) * 0.07,
      P.pos.z + leanRight.z * leanOff,
    );
    camera.rotation.set(P.pitch - aimPunchP, P.yaw + aimPunchY, -leanAmt * 0.14);
    if (shake > 0.001) {
      const t9 = gameT * 31;
      camera.rotation.x += Math.sin(t9 * 1.3 + 1.7) * 0.014 * shake;
      camera.rotation.y += Math.sin(t9 * 1.1 + 4.2) * 0.014 * shake;
      camera.rotation.z += Math.sin(t9 * 1.7 + 2.9) * 0.01 * shake;
    }
    const horSpeed = Math.hypot(P.vel.x, P.vel.z);
    const bobAmt = (P.onGround ? clamp(horSpeed / 6, 0, 1) : 0) * (1 - adsAmt * 0.85);
    camera.position.y += Math.sin(bobT * 2) * 0.024 * bobAmt;
    camera.rotation.z = Math.sin(bobT) * 0.005 * bobAmt + (slideT > 0 ? 0.055 : 0) - leanAmt * 0.14;
    swayX = damp(swayX, 0, 9, dt);
    swayY = damp(swayY, 0, 9, dt);
    const vm = bombOut ? vmC4 : nadeOut ? vmNade : vms[cur];
    const base = vm.userData.base as THREE.Vector3;
    const adsPos = (vm.userData.adsPos as THREE.Vector3) || base;
    gunKick = Math.max(0, gunKick - dt * 10);
    const px = base.x + (adsPos.x - base.x) * adsAmt;
    const py = base.y + (adsPos.y - base.y) * adsAmt;
    const pz = base.z + (adsPos.z - base.z) * adsAmt;
    const swayMul = 1 - adsAmt * 0.8;
    stepPunch = Math.max(0, stepPunch - dt * 0.45);
    const breathe = Math.sin(gameT * 1.7) * 0.0022 * (1 - bobAmt) * (1 - adsAmt);
    vm.position.set(
      px + Math.sin(bobT) * 0.007 * bobAmt + sprintAmt * 0.06 - swayX * 0.0008 * swayMul,
      py + Math.abs(Math.sin(bobT * 2)) * 0.009 * bobAmt + breathe - (P.crouching ? 0.015 : 0) - sprintAmt * 0.08 + swayY * 0.0006 * swayMul - stepPunch * (1 - adsAmt),
      pz + kick * (0.05 + adsAmt * 0.03) + landDip * 0.25,
    );
    vm.rotation.set(
      kick * 0.14 + sprintAmt * 0.55 - swayY * 0.0011 * swayMul,
      sprintAmt * 0.3 + swayX * 0.0015 * swayMul,
      sprintAmt * 0.12 + (slideT > 0 ? 0.1 : 0),
    );
    if (!nadeOut && !bombOut) vm.visible = !(cur === "sniper" && adsAmt > 0.75);
    if (def.melee && knifeT > 0) {
      knifeT -= dt;
      const sw = Math.sin(((0.25 - knifeT) / 0.25) * Math.PI);
      vm.rotation.x = -sw * 0.9;
      vm.position.z = pz - sw * 0.18;
    }
    if (reloadT > 0) {
      // 3-phase reload: tilt+drop mag -> insert -> charge (three-fps style FSM, procedural)
      const total = def.reloadT;
      const ph = 1 - reloadT / total; // 0..1
      if (ph < 0.35) {
        const k2 = ph / 0.35;
        vm.rotation.x = -0.5 * k2;
        vm.rotation.z = 0.18 * k2;
        vm.position.y = py - 0.1 * k2;
      } else if (ph < 0.7) {
        const k2 = (ph - 0.35) / 0.35;
        vm.rotation.x = -0.5 + 0.25 * k2;
        vm.rotation.z = 0.18 - 0.06 * k2;
        vm.position.y = py - 0.1 + 0.05 * Math.sin(k2 * Math.PI);
      } else {
        const k2 = (ph - 0.7) / 0.3;
        vm.rotation.x = -0.25 * (1 - k2);
        vm.rotation.z = 0.12 * (1 - k2);
        vm.position.y = py - 0.05 * (1 - k2);
        vm.position.z = pz + 0.04 * Math.sin(k2 * Math.PI); // charge handle jolt
      }
    }
    if (switchT > 0) {
      vm.position.y = py - switchT * 0.55;
      vm.rotation.x = switchT * 1.3;
    }
    if (inspectT > 0 && !nadeOut) {
      inspectT -= dt;
      if (mouseDown || ads || reloadT > 0) inspectT = 0;
      else {
        // keyframed CS-style inspect: lift, show left flank, tilt to glance the top, settle back.
        // columns: [phase, addYaw, addRoll, addPitch, dx, dy, dz]
        const ph2 = 1 - inspectT / INSPECT_LEN; // 0..1
        const K: number[][] = [
          [0.0,  0,     0,     0,     0,     0,     0],
          [0.18, 0.72,  0.3,  -0.06, -0.07,  0.045, 0.05],
          [0.42, 0.95,  0.42, -0.1,  -0.09,  0.07,  0.09],
          [0.62, 0.5,  -0.38,  0.1,  -0.05,  0.05,  0.06],
          [0.82, -0.18, -0.14, 0.04, -0.015, 0.015, 0.02],
          [1.0,  0,     0,     0,     0,     0,     0],
        ];
        let ki = 0;
        while (ki < K.length - 2 && ph2 > K[ki + 1][0]) ki++;
        const ka = K[ki], kb = K[ki + 1];
        const tt = clamp((ph2 - ka[0]) / Math.max(1e-4, kb[0] - ka[0]), 0, 1);
        const e2 = tt * tt * (3 - 2 * tt); // smoothstep between keys
        const L = (j: number) => ka[j] + (kb[j] - ka[j]) * e2;
        vm.rotation.y += L(1);
        vm.rotation.z += L(2);
        vm.rotation.x += L(3);
        vm.position.x = px + L(4);
        vm.position.y = py + L(5);
        vm.position.z = pz + L(6);
      }
    }
    if (throwAnimT > 0) {
      throwAnimT -= dt;
      const sw = Math.sin(((0.34 - throwAnimT) / 0.34) * Math.PI);
      vm.rotation.x = -sw * 1.35;
      vm.rotation.z = sw * 0.35;
      vm.position.y = py - sw * 0.16;
      vm.position.x = px + sw * 0.07;
    }
    if (nadeOut && nadeDrawT > 0) {
      vm.position.y = py - nadeDrawT * 1.6;
      vm.rotation.x = nadeDrawT * 3.2;
    }
    if (nadeOut && throwAnimT <= 0 && nadeDrawT <= 0) {
      // gentle idle wobble while holding the grenade
      vm.rotation.z += Math.sin(gameT * 1.3) * 0.015;
      vm.rotation.x += Math.sin(gameT * 0.9) * 0.01;
    }
  }

  // ============ HUD tick ============
  function hudTick(dt: number) {
    elHp.textContent = String(Math.max(0, P.hp));
    elHp.style.color = P.hp > 60 ? "#9fe870" : P.hp > 25 ? "#ffd76e" : "#ff6b5a";
    elArmor.textContent = String(P.armor);
    const def = WEAPONS[cur];
    if (bombOut) {
      elWname.textContent = "C4 EXPLOSIVE";
      if (lastIcon !== "/tex/w_frag.png") { lastIcon = "/tex/w_frag.png"; elWicon.src = "/tex/w_frag.png"; }
      elMag.textContent = "1"; elRes.textContent = "";
    } else if (nadeOut) {
      elWname.textContent = nadeSel === "frag" ? "FRAG GRENADE" : "SMOKE GRENADE";
      if (lastIcon !== "/tex/w_frag.png") { lastIcon = "/tex/w_frag.png"; elWicon.src = "/tex/w_frag.png"; }
      elMag.textContent = String(nadeSel === "frag" ? frags : smokes); elRes.textContent = "";
    } else {
      elWname.textContent = def.name;
      const iconSrc = WEAPON_ICONS[cur];
      if (iconSrc !== lastIcon) { lastIcon = iconSrc; elWicon.src = iconSrc; }
      if (def.melee) { elMag.textContent = "—"; elRes.textContent = ""; }
      else { elMag.textContent = String(ammo[cur].mag); elRes.textContent = " / " + ammo[cur].res; }
    }
    elMoney.textContent = "$" + P.money;
    elNades.innerHTML = "<span style='color:" + (nadeOut && nadeSel === "frag" ? "#ffd76e" : "#717a84") + "'>[4] FRAG x" + frags + "</span> · <span style='color:" + (nadeOut && nadeSel === "smoke" ? "#7fb3ff" : "#717a84") + "'>SMOKE x" + smokes + "</span>" + (defuse && attacking && playerCarrier ? " · <span style='color:" + (bombOut ? "#ff7a4a" : "#717a84") + "'>[5] C4</span>" : "") + " · <span style='color:#717a84'>[G] drop</span>";
    const t = Math.max(0, Math.ceil(phaseT));
    if (mp) {
      const dmT = Math.max(0, Math.ceil(dmTime));
      elTimer.textContent = Math.floor(dmT / 60) + ":" + String(dmT % 60).padStart(2, "0");
    } else {
      elTimer.textContent = phase === "end" ? "—" : Math.floor(t / 60) + ":" + String(t % 60).padStart(2, "0");
    }
    elTimer.style.color = phase === "live" && phaseT < 16 ? "#ff6b5a" : "#e8e3d6";
    elPhase.textContent = phase === "buy" ? "BUY PHASE — PRESS B FOR BUY MENU" : "";
    elScope.style.display = cur === "sniper" && adsAmt > 0.6 ? "block" : "none";
    {
      const scopeFade = cur === "sniper" && !nadeOut ? 1 - adsAmt : 1; // only the scope replaces the crosshair
      elXhair.style.opacity = String(Math.max(0, scopeFade * (1 - sprintAmt * 0.75)));
    }
    elHpBar.style.width = Math.max(0, P.hp) + "%";
    elHpBar.style.background = P.hp > 60 ? "#9fe870" : P.hp > 25 ? "#ffd76e" : "#ff6b5a";
    elLowHp.style.opacity = P.alive && P.hp <= 30 ? String(0.5 + Math.sin(gameT * 6) * 0.25) : "0";
    if (defuse && bombState === "planted") {
      elBombTimer.style.display = "block";
      elBombTimer.textContent = "C4 0:" + String(Math.max(0, Math.ceil(bombT))).padStart(2, "0");
      elBombTimer.style.color = bombT < 10 ? "#ff2f1a" : "#ff5f4a";
    } else elBombTimer.style.display = "none";
    if (dmgDirT > 0) {
      dmgDirT -= dt;
      elDmgDir.style.opacity = String(clamp(dmgDirT / 1.1, 0, 1));
      elDmgDir.style.transform = "translate(-50%,-50%) rotate(" + dmgDirAngle.toFixed(1) + "deg)";
    } else elDmgDir.style.opacity = "0";
    radarAcc += dt;
    if (radarAcc > 0.08) { radarAcc = 0; drawRadar(); }
    if (killFlashT > 0) {
      killFlashT -= dt;
      const k3 = clamp(killFlashT / 0.4, 0, 1);
      elHitmark.style.opacity = String(Math.max(k3, hitmarkT > 0 ? clamp(hitmarkT / 0.18, 0, 1) : 0));
      elHitmark.style.transform = "translate(-50%,-50%) rotate(45deg) scale(" + (1 + (1 - k3) * 0.6) + ")";
      elHitmark.querySelectorAll<HTMLElement>("div").forEach((el) => { el.style.background = "#ff5240"; });
    } else if (hitmarkT > 0) {
      hitmarkT -= dt;
      elHitmark.style.opacity = String(clamp(hitmarkT / 0.18, 0, 1));
      elHitmark.style.transform = "translate(-50%,-50%) rotate(45deg) scale(1)";
      elHitmark.querySelectorAll<HTMLElement>("div").forEach((el) => { el.style.background = "#fff"; });
    }
    if (dmgT > 0) { dmgT -= dt; elDmg.style.opacity = String(clamp(dmgT / 0.45, 0, 1) * 0.9); } else elDmg.style.opacity = "0";
    if (centerT > 0) { centerT -= dt; if (centerT <= 0) elCenter.textContent = ""; }
    const moving = Math.hypot(P.vel.x, P.vel.z) > 2 ? 6 : 0;
    const gap = (6 + moving + recoilHeat * 2.4) * (1 - adsAmt * 0.55);
    const lines = elXhair.querySelectorAll(".xl");
    (lines[0] as HTMLElement).style.transform = "translateY(" + (-gap + 10) + "px)";
    (lines[1] as HTMLElement).style.transform = "translateY(" + (gap - 10) + "px)";
    (lines[2] as HTMLElement).style.transform = "translateX(" + (-gap + 10) + "px)";
    (lines[3] as HTMLElement).style.transform = "translateX(" + (gap - 10) + "px)";
  }

  // ============ defuse: per-frame logic ============
  function bombTick(dt: number) {
    if (beepAcc > 0) beepAcc -= dt;
    const led = bombG.userData.led as THREE.Mesh | undefined;
    if (bombState === "planted") {
      bombT -= dt;
      const interval = bombT > 20 ? 1.0 : bombT > 10 ? 0.55 : bombT > 5 ? 0.3 : 0.15;
      if (beepAcc <= 0) {
        beepAcc = interval;
        const pv = panVol(bombG.position);
        blip(1240, 0.045, clamp(0.3 * pv.mul + 0.06, 0.06, 0.3), "square");
        if (led) (led.material as THREE.MeshStandardMaterial).emissiveIntensity = 4;
      }
      if (led) { const m = led.material as THREE.MeshStandardMaterial; m.emissiveIntensity = Math.max(0.6, m.emissiveIntensity - dt * 9); }
      if (bombT <= 0) { explodeBomb(); return; }
    }
    if (phase !== "live" || !P.alive) { elBombAct.style.display = "none"; return; }
    const holdingE = keys.has("KeyE");
    if (attacking && playerCarrier && bombState === "carried") {
      const st = siteAt(P.pos.x, P.pos.z);
      if (st) {
        elBombAct.style.display = "block";
        if (!bombOut) {
          playerPlantT = 0;
          elBombActLabel.textContent = "PRESS 5 TO TAKE OUT THE C4 — SITE " + st;
          elBombActBar.style.width = "0%";
        } else if (holdingE || mouseDown) {
          playerPlantT += dt;
          elBombActLabel.textContent = "PLANTING...";
          elBombActBar.style.width = Math.min(100, (playerPlantT / PLANT_LEN) * 100) + "%";
          if (playerPlantT >= PLANT_LEN) {
            playerCarrier = false; bombOut = false; refreshVM();
            plantBomb(P.pos.x, P.pos.z, "YOU");
            P.money = Math.min(9000, P.money + 300);
            switchWeapon(owned[lastGunWeapon] ? lastGunWeapon : "knife");
            elBombAct.style.display = "none";
          }
        } else {
          playerPlantT = 0;
          elBombActLabel.textContent = "HOLD LMB OR E TO PLANT — SITE " + st;
          elBombActBar.style.width = "0%";
        }
        return;
      }
      playerPlantT = 0;
      if (bombOut) { elBombAct.style.display = "block"; elBombActLabel.textContent = "GET TO A BOMB SITE (A / B)"; elBombActBar.style.width = "0%"; return; }
    }
    if (attacking && bombState === "dropped" && Math.hypot(P.pos.x - bombX, P.pos.z - bombZ) < 1.4) {
      bombState = "carried"; playerCarrier = true; bombG.visible = false;
      centerMsg("YOU HAVE THE BOMB", 1.6, "#ffd76e");
    }
    if (!attacking && bombState === "planted" && Math.hypot(P.pos.x - bombX, P.pos.z - bombZ) < 1.7) {
      const need = hasKit ? DEFUSE_KIT_LEN : DEFUSE_LEN;
      elBombAct.style.display = "block";
      if (holdingE) {
        playerDefuseT += dt;
        elBombActLabel.textContent = "DEFUSING..." + (hasKit ? " (KIT)" : "");
        elBombActBar.style.width = Math.min(100, (playerDefuseT / need) * 100) + "%";
        if (playerDefuseT >= need) { defuseDone("YOU"); P.money = Math.min(9000, P.money + 300); elBombAct.style.display = "none"; }
      } else {
        playerDefuseT = Math.max(0, playerDefuseT - dt * 2);
        elBombActLabel.textContent = "HOLD E TO DEFUSE" + (hasKit ? " (KIT 3.5s)" : " (7s)");
        elBombActBar.style.width = (playerDefuseT / need) * 100 + "%";
      }
      return;
    }
    elBombAct.style.display = "none";
  }

  // ============ main loop ============
  let last = performance.now();
  let raf = 0;
  function frame(now: number) {
    raf = requestAnimationFrame(frame);
    let dt = Math.min(0.05, (now - last) / 1000);
    last = now;
    if (hitStopT > 0) { hitStopT -= dt; dt *= 0.3; } // kill confirm micro slow-mo
    gameT += dt;
    if (mp) {
      mpTick(dt);
    } else if (matchOver) {
      // frozen between matches
    } else {
    phaseT -= dt;
    if (phase === "end" && phaseT <= 0) {
      if (myScore >= ROUND_TARGET || enemyScore >= ROUND_TARGET) {
        showMatchEnd(myScore >= ROUND_TARGET);
      } else startRound();
    } else if (phase === "buy" && phaseT <= 0) {
      phase = "live"; phaseT = LIVE_TIME;
      if (buyOpen) { buyOpen = false; elBuy.style.display = "none"; canvas.requestPointerLock(); }
      centerMsg("GO GO GO", 1.4, "#9fe870");
    } else if (phase === "live" && phaseT <= 0) {
      if (defuse && bombState === "planted") { /* bomb timer rules */ }
      else if (defuse) endRound(attacking ? false : true, "TIME EXPIRED — DEFENDERS HOLD");
      else endRound(false, "TIME EXPIRED");
    }
    }
    if (!boardOpen && !buyOpen && !matchOver) playerTick(dt);
    if (!mp || isHost) {
      const botDt = phase === "buy" ? 0 : dt;
      for (const b of bots) botUpdate(b, b.alive ? botDt : dt);
    }
    if (defuse && !mp) bombTick(dt);
    cameraTick(dt);
    updateNades(dt);
    updateDrops(dt);
    updateRagdolls(dt);
    updateFX(dt);
    hudTick(dt);
    renderer.render(scene, camera);
  }
  if (mp) startMP(); else startRound();
  raf = requestAnimationFrame(frame);

  // ============ cleanup ============
  return () => {
    cancelAnimationFrame(raf);
    netDead = true;
    bcSend({ t: "leave" });
    conns.forEach((c) => { try { c.close(); } catch { /* already closed */ } });
    conns.clear();
    if (peer) { try { peer.destroy(); } catch { /* already destroyed */ } peer = null; }
    ac.abort();
    if (document.pointerLockElement === canvas) document.exitPointerLock();
    if (actx) actx.close().catch(() => undefined);
    renderer.dispose();
    scene.traverse((o) => {
      if (o instanceof THREE.Mesh || o instanceof THREE.Sprite || o instanceof THREE.Line) {
        const m = o as THREE.Mesh;
        if (m.geometry) m.geometry.dispose();
        const mat = m.material as THREE.Material | THREE.Material[];
        if (Array.isArray(mat)) mat.forEach((x) => x.dispose());
        else if (mat) mat.dispose();
      }
    });
    container.removeChild(canvas);
    container.removeChild(hud);
  };
}
