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
  // 길드별 '오늘 처리'(today)는 미추적이면 null — 가짜 0 대신 항목 자체를 생략(제공 모델 수만 표시).
  const today = srv.today != null ? ' · 오늘 ' + srv.today + '건' : '';
  switch (srv.state) {
    case ProviderState.PENDING: return '관리자 승인 대기';
    case ProviderState.PAUSED: return '제공 모델 ' + (srv.models ?? 0);
    default: return '제공 모델 ' + (srv.models ?? 0) + today;
  }
};

// 내 권한 배지 → { label, cls }  (cls 는 CSS .srv-role.{admin|provider})
const ROLE_PRESENT = {
  [Role.ADMIN]: { label: '관리자', cls: 'admin' },
  [Role.PROVIDER]: { label: '기부자', cls: 'provider' },
};
export const presentRole = (role) => ROLE_PRESENT[role] || ROLE_PRESENT[Role.PROVIDER];

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
