# Stochastic Strength

A strength training app that handles the thinking so you can focus on lifting.

## The Idea

Most workout apps ask you to plan your workouts, track your own progress, and
figure out when to increase weight. Stochastic Strength does all of that
automatically.

The core philosophy is that **consistency beats optimization**. The best workout
program is the one you actually do. So the app minimizes friction: open it, do
what it says, tell it how it felt, close it. That's it.

The "stochastic" part means your workout is randomly generated each session from
a library of exercises. This keeps training varied, prevents overuse patterns,
and removes the mental overhead of deciding what to do. The randomness is
structured - no more than two exercises per muscle group per session, and always
balanced across the body.

## How It Works

**Before you lift**, the app generates a 6-exercise workout based on what
equipment is available where you are. It uses your location to know whether
you're at a full gym, a home setup, or somewhere else entirely, and only
programs exercises you can actually do.

**During the workout**, the app walks you through each set with the right weight
for you today. After each working set, you log how it felt using Reps In Reserve
(RIR) - roughly, how many more reps could you have done? The options are simple:
too hard, hurt, almost out of gas, something left, or very easy.

**After each session**, the app adjusts your weights for every muscle group
based on your feedback. Nail your sets? Weight goes up next time. Struggling? It
backs off. Something caused pain? It drops the weight significantly and
remembers to be cautious with that exercise.

Over time, the weights dial in to exactly where you should be - not where some
generic program says you should be.

## Features

- **Auto-progression**: weights adjust after every session based on how your sets actually felt
- **Location-aware equipment**: knows what's available at your gym, home, or wherever you are
- **Warmup sets**: automatically calculated before each working set
- **Rest timer**: 90-second countdown between sets with notifications so you can put your phone down
- **Strava export**: sessions export as strength workouts to your Strava feed
- **Workout history**: see your progress over time

## The Feedback System

The RIR (Reps In Reserve) scale is how the app learns your current fitness level:

| What you tap | What it means                                           |
| ------------ | ------------------------------------------------------- |
| 5+ left      | Very easy - weight goes up significantly                |
| 2-4 left     | Solid working set - modest increase                     |
| 0-1 left     | Near max effort - small increase                        |
| Too Hard     | Couldn't complete the set - weight drops                |
| Hurt         | Something hurt - weight drops sharply, exercise flagged |

The progression is conservative by design: the app always prefers to be slightly
under rather than over, because an injury sets you back further than a few easy
sessions.

## Requirements

Android 13 or newer.
