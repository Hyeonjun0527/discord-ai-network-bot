// ════════════════════════════════════════════════════════════════════════
// Presenter — 도메인 상태(백엔드 enum) → UI 표현(라벨·점 색) 매핑.
// UI 는 ProviderState 같은 도메인 값을 받아 여기서 표현으로 바꾼다.
// 임의 용어(ok/error)를 UI 에 박지 않기 위함. 디자인 언어 점 언어: ●정상 ⏸일시중지 ⚠경고.
// ════════════════════════════════════════════════════════════════════════
import { ProviderState, Role } from './contract.js';
import { t } from './i18n.js';

// 서버 제공 상태 → { label, dot }  (dot 은 CSS .srv-st.{ok|paused|pending|error})
const SERVER_PRESENT = {
  [ProviderState.ONLINE_IDLE]: { k: 'presenterServerStateConnected', dot: 'ok' },
  [ProviderState.ONLINE_BUSY]: { k: 'presenterServerStateResponding', dot: 'ok' },
  [ProviderState.APPROVED]:    { k: 'presenterServerStateConnected', dot: 'ok' },
  [ProviderState.PAUSED]:      { k: 'presenterServerStatePaused', dot: 'paused' },
  [ProviderState.LIMITED]:     { k: 'presenterServerStateLimitReached', dot: 'pending' },
  [ProviderState.PENDING]:     { k: 'presenterServerStateApprovalNeeded', dot: 'pending' },
  [ProviderState.OFFLINE]:     { k: 'presenterServerStateOffline', dot: 'error' },
  [ProviderState.UNHEALTHY]:   { k: 'presenterServerStateMaintenance', dot: 'error' },
  [ProviderState.REMOVED]:     { k: 'presenterServerStateRemoved', dot: 'error' },
};
// 라벨은 호출 시점에 t() 로 — 언어 전환 시 화면 재렌더가 새 언어를 반영한다.
export const presentServerState = (state) => { const p = SERVER_PRESENT[state]; return p ? { label: t(p.k), dot: p.dot } : { label: state, dot: 'paused' }; };

// 서버 메타 줄(상태별 부가정보)
export const presentServerMeta = (srv) => {
  // 길드별 '오늘 처리'(today)는 미추적이면 null — 가짜 0 대신 항목 자체를 생략(제공 모델 수만 표시).
  const today = srv.today != null ? t('presenterServerMetaToday').replace('{n}', srv.today) : '';
  switch (srv.state) {
    case ProviderState.PENDING: return t('presenterServerMetaAdminApprovalPending');
    case ProviderState.PAUSED: return t('presenterServerMetaAvailableModels').replace('{n}', srv.models ?? 0);
    default: return t('presenterServerMetaAvailableModels').replace('{n}', srv.models ?? 0) + today;
  }
};

// 내 권한 배지 → { label, cls }  (cls 는 CSS .srv-role.{admin|provider})
const ROLE_PRESENT = {
  [Role.ADMIN]: { k: 'presenterRoleAdmin', cls: 'admin' },
  [Role.PROVIDER]: { k: 'presenterRoleDonor', cls: 'provider' },
};
export const presentRole = (role) => { const p = ROLE_PRESENT[role] || ROLE_PRESENT[Role.PROVIDER]; return { label: t(p.k), cls: p.cls }; };

// 공개 대상(scope)은 UI 에서 제거됨 — '서버 멤버 누구나'(ALL)는 길드별 라우팅 격리로 이미
// 구조적으로 보장되고(다른 서버 멤버는 호출 불가), 세분화(신뢰 역할/관리자만)는 강제되지 않아
// 가짜 컨트롤이 되므로 노출하지 않는다. 화면엔 보장되는 사실만 고정 문구로 표시한다.

// 에이전트 전체 제공 상태 → 홈 히어로 표현 키(현재 heroState STATES: ok/paused/error)
// AgentStatus(running/connected) 또는 ProviderState 를 단일 히어로 상태로 환산.
export const presentAgentHero = (status) => {
  if (!status) return 'error';
  if (status.state) { // ProviderState 가 오면 우선
    if (status.state === ProviderState.PAUSED) return 'paused';
    if ([ProviderState.OFFLINE, ProviderState.UNHEALTHY].includes(status.state)) return 'error';
    return 'ok';
  }
  if (status.paused) return 'paused';
  if (!status.connected) return 'error';
  return 'ok';
};
