# Contributing to ChatSphere

Thanks for considering a contribution! This project is a portfolio-grade
real-time chat application, and the bar for changes is "would this survive a
senior engineer's code review?"

## Development setup

1. Fork & clone the repository.
2. `cp .env.example .env` and set a real `JWT_SECRET` (`openssl rand -base64 48`).
3. Start everything: `docker-compose up --build`, or run the pieces manually
   (see the README's *Manual setup* section).
4. Backend tests: `cd backend && mvn verify` (includes the JaCoCo 80% coverage gate).
5. Frontend build: `cd frontend && npm install && npm run build`.

## Branching & commits

- Branch from `main`: `feat/…`, `fix/…`, `docs/…`, `chore/…`.
- Use [Conventional Commits](https://www.conventionalcommits.org/):
  `feat: add message reactions`, `fix: prevent duplicate read receipts`.
- Keep PRs focused — one feature or fix per PR.

## Pull request checklist

- [ ] `mvn verify` passes locally (tests + coverage gate)
- [ ] New logic has tests (unit for services, MockMvc/STOMP for endpoints)
- [ ] No secrets in code — everything configurable via environment variables
- [ ] README / API docs updated if behavior changed
- [ ] UI changes include a screenshot in the PR description

## Code style

**Backend (Java 17 / Spring Boot 3.2)**
- Constructor injection, no field `@Autowired`.
- Records for DTOs; entities stay internal — map to DTOs at the boundary.
- Validate all external input (`jakarta.validation` + service-level checks).
- Never trust client-supplied identity; always derive it from the `Principal`.
- Comment the *why*, not the *what*.

**Frontend (React 18)**
- Functional components + hooks only.
- Keep WebSocket frame handlers pure and idempotent (frames can be redelivered).
- Inline styles or small style objects; no CSS framework.

## Reporting issues

Open a GitHub issue with:
- What you expected vs. what happened
- Steps to reproduce (include browser + OS for UI bugs)
- Relevant logs (`docker compose logs backend`)

## Security issues

Please **do not** open public issues for vulnerabilities. Email the maintainer
(see README author section) with details and steps to reproduce.
