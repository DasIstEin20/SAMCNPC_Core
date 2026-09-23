# 0106: Idle control stops input, not physical velocity

Status: accepted, 2026-09-23. Audit A7.

Native baseline tests reproduced zero horizontal displacement after actual
LivingEntity knockback, identical zero sliding on stone and ice, and deletion of
an external impulse by stopControl. The cause is unconditional horizontal velocity
reset both every idle tick and at explicit stop/lease expiry.

Remove those resets. Idle/stop/expiry still clear direct input, navigation, sprint
and crouch according to their existing lifecycle, but LivingEntity owns momentum,
friction, collision and externally applied impulses. No autonomous movement or
new braking policy is added to Core. A caller requesting stop releases input;
it does not gain a means to cancel knockback. Keep existing lease/idle tests and
add native knockback, stop-with-impulse and comparative ice-friction regressions.

The recorded bent-trunk native regression exposed a Behavior assumption: between
begin and next-level validation, residual motion can move the feet cell over air
while the body is still grounded on an adjacent support. Re-observe that support
with the existing bounded candidate search, and let normal friction settle motion
before progressing from positioning to equip/jump. The original 40-step positioning
deadline still applies. Unit reproductions failed before both corrections; native
dark oak returned from 31/40 to 40/40 logs with initial stock and cleanup preserved.
The 64-scanner fixture also required entity-tracking readiness before item pickup;
successful addFreshEntity alone did not imply UUID visibility in its loaded chunks.
