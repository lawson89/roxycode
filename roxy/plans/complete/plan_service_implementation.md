# Goal
Create a PlanService that handles the creation, updating, deleting and modifying of plans.

# Proposed Changes
- Create `PlanStatus` enum.
- Create `Plan` data object.
- Create `PlanService` with methods for creating, updating, deleting, moving, and listing plans.
- Break circular dependency in `ScriptServiceRegistry` by using `Provider`.

# Implementation Steps
- [x] Create PlanStatus enum
- [x] Create Plan data object
- [x] Implement PlanService with CRUD operations
- [x] Add @ScriptService and @LLMDoc annotations
- [x] Break circular dependency in ScriptServiceRegistry
- [x] Create and pass PlanServiceTest

# Implementation Progress
- [x] Completed all steps.
