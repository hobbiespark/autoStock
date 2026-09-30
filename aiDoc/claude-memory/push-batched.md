---
name: push-batched
description: autoStock에서 push는 작업마다 하지 않고 사용자가 모아서 한 번에 요청한다
metadata:
  node_type: memory
  type: feedback
  originSessionId: 12e8e243-e461-4f8e-9a59-3c0970a97a69
  modified: 2026-09-30T01:01:50.801Z
---

커밋 뒤에 push를 제안하거나 묻지 않는다. push는 사용자가 한 번에 요청할 때만 한다.

**Why:** 사용자가 "push는 한번에 합니다"라고 명시했다(2026-09-30, 리팩토링 커밋을 여러 개 쌓는 중).

**How to apply:** 커밋 보고 끝에 "push할까요?"를 붙이지 않는다. "로컬이 N커밋 앞섬" 정도의 상태만 알린다.
