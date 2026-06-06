// ════════════════════════════════════════════════════════════════════════
// Presenter — 도메인 상태(백엔드 enum) → UI 표현(라벨·점 색) 매핑.
// UI 는 ProviderState 같은 도메인 값을 받아 여기서 표현으로 바꾼다.
// 임의 용어(ok/error)를 UI 에 박지 않기 위함. 디자인 언어 점 언어: ●정상 ⏸일시중지 ⚠경고.
// ════════════════════════════════════════════════════════════════════════
import { ProviderState, Role } from './contract.js';

// 서버 제공 상태 → { label, dot }  (dot 은 CSS .srv-st.{ok|paused|pending|error})
const SERVER_PRESENT = {
  [ProviderState.ONLINE_IDLE]: { label: '연결됨', dot: 'ok' },
  [ProviderState.ONLINE_BUSY]: { label: '응답 중', dot: 'ok' },
  [ProviderState.APPROVED]:    { label: '연결됨', dot: 'ok' },
  [ProviderState.PAUSED]:      { label: '일시중지', dot: 'paused' },
  [ProviderState.LIMITED]:     { label: '한도 도달', dot: 'pending' },
  [ProviderState.PENDING]:     { label: '승인 필요', dot: 'pending' },
  [ProviderState.OFFLINE]:     { label: '연결 끊김', dot: 'error' },
  [ProviderState.UNHEALTHY]:   { label: '점검 필요', dot: 'error' },
  [ProviderState.REMOVED]:     { label: '제거됨', dot: 'error' },
};
export const presentServerState = (state) => SERVER_PRESENT[state] || { label: state, dot: 'paused' };

// 서버 메타 줄(상태별 부가정보)
export const presentServerMeta = (srv) => {
  switch (srv.state) {
    case ProviderState.PENDING: return '관리자 승인 대기';
    case ProviderState.PAUSED: return '제공 모델 ' + (srv.models ?? 0);
    default: return '제공 모델 ' + (srv.models ?? 0) + ' · 오늘 ' + (srv.today ?? 0) + '건';
  }
};

// 내 권한 배지 → { label, cls }  (cls 는 CSS .srv-role.{admin|provider})
const ROLE_PRESENT = {
  [Role.ADMIN]: { label: '관리자', cls: 'admin' },
  [Role.PROVIDER]: { label: '기부자', cls: 'provider' },
};
export const presentRole = (role) => ROLE_PRESENT[role] || ROLE_PRESENT[Role.PROVIDER];

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
