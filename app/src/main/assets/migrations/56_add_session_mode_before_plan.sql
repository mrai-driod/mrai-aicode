-- Record the mode before entering PLAN mode (e.g. AUTO), so it can be restored when leaving PLAN:
-- Previously, leaving PLAN always fell back to BUILD, overwriting the auto mode of users who entered PLAN from AUTO.
ALTER TABLE chat_sessions ADD COLUMN modeBeforePlan TEXT DEFAULT NULL;