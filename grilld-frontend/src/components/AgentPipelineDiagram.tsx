"use client";

import { useMemo } from "react";
import {
  ReactFlow,
  Background,
  BackgroundVariant,
  Handle,
  Position,
  type Edge,
  type Node,
  type NodeProps,
} from "@xyflow/react";
import {
  Lightbulb,
  MessagesSquare,
  Network,
  FileText,
  LineChart,
  Swords,
  Compass,
  Cpu,
  Server,
  GitBranch,
  Map,
  GraduationCap,
  FileCode2,
  ShieldCheck,
} from "lucide-react";
import { cn } from "@/lib/utils";

type Variant = "idea" | "process" | "agent" | "output";

interface PipelineNodeData extends Record<string, unknown> {
  label: string;
  sublabel?: string;
  icon: React.ComponentType<{ className?: string }>;
  variant: Variant;
}

const VARIANT_STYLES: Record<Variant, string> = {
  idea: "border-line bg-paper text-ink",
  process: "border-accent/40 bg-accent-soft text-accent-ink",
  agent: "border-line bg-paper-raised text-ink",
  output: "border-accent bg-ink text-paper",
};

function PipelineNode({ data }: NodeProps<Node<PipelineNodeData>>) {
  const Icon = data.icon;
  return (
    <div
      className={cn(
        "flex items-center gap-2.5 rounded-lg border px-3.5 py-2.5 shadow-sm",
        data.variant === "agent" ? "w-[172px]" : "w-[180px]",
        VARIANT_STYLES[data.variant],
      )}
    >
      <Handle type="target" position={Position.Left} className="!border-none !bg-line" />
      <Icon className={cn("h-4 w-4 shrink-0", data.variant === "output" ? "text-paper" : "text-accent-ink")} />
      <div className="min-w-0">
        <p className={cn("truncate text-sm font-medium", data.variant === "agent" && "text-xs")}>{data.label}</p>
        {data.sublabel && (
          <p className={cn("truncate text-xs", data.variant === "output" ? "text-paper/70" : "text-ink-soft")}>
            {data.sublabel}
          </p>
        )}
      </div>
      <Handle type="source" position={Position.Right} className="!border-none !bg-line" />
    </div>
  );
}

const nodeTypes = { pipeline: PipelineNode };

const AGENTS: { label: string; icon: React.ComponentType<{ className?: string }> }[] = [
  { label: "Market Analyst", icon: LineChart },
  { label: "Competition Analyst", icon: Swords },
  { label: "Strategy Agent", icon: Compass },
  { label: "Tech Architect", icon: Cpu },
  { label: "Infra Agent", icon: Server },
  { label: "Diagram Agent", icon: GitBranch },
  { label: "Roadmap Agent", icon: Map },
  { label: "Skills Curator", icon: GraduationCap },
  { label: "Agent-File Writer", icon: FileCode2 },
  { label: "Consistency Auditor", icon: ShieldCheck },
];

const ROW_GAP = 46;
const AGENTS_TOP = 0;
const CENTER_Y = AGENTS_TOP + ((AGENTS.length - 1) * ROW_GAP) / 2;

const COLUMN_X = { idea: 0, interrogation: 195, orchestrator: 410, agents: 660, output: 940 };

/**
 * A static (no pan/zoom/drag) React Flow diagram - the one component of the
 * four curated kits (prompt-kit/Motion Primitives/Aceternity/Watermelon) that
 * actually does node-and-edge graphs is none of them, so per
 * frontend-component-kits' own escape hatch this reaches for @xyflow/react
 * instead, the de facto standard for exactly this shape of diagram.
 * Every node position is hand-computed, not algorithmic (dagre/elkjs) - the
 * tree shape is fixed content, not dynamic data, so a layout engine would be
 * pure overhead.
 */
export function AgentPipelineDiagram() {
  const nodes = useMemo<Node<PipelineNodeData>[]>(
    () => [
      {
        id: "idea",
        type: "pipeline",
        position: { x: COLUMN_X.idea, y: CENTER_Y },
        data: { label: "Your idea", sublabel: "One sentence", icon: Lightbulb, variant: "idea" },
      },
      {
        id: "interrogation",
        type: "pipeline",
        position: { x: COLUMN_X.interrogation, y: CENTER_Y },
        data: { label: "Interrogation", sublabel: "A few sharp questions", icon: MessagesSquare, variant: "process" },
      },
      {
        id: "orchestrator",
        type: "pipeline",
        position: { x: COLUMN_X.orchestrator, y: CENTER_Y },
        data: { label: "Orchestrator", sublabel: "Fans out the brief", icon: Network, variant: "process" },
      },
      ...AGENTS.map((agent, i) => ({
        id: `agent-${i}`,
        type: "pipeline",
        position: { x: COLUMN_X.agents, y: AGENTS_TOP + i * ROW_GAP },
        data: { label: agent.label, icon: agent.icon, variant: "agent" as const },
      })),
      {
        id: "output",
        type: "pipeline",
        position: { x: COLUMN_X.output, y: CENTER_Y },
        data: { label: "Your blueprint", sublabel: "Docs, diagrams, agent kit", icon: FileText, variant: "output" },
      },
    ],
    [],
  );

  const edges = useMemo<Edge[]>(
    () => [
      { id: "e-idea-interrogation", source: "idea", target: "interrogation", type: "bezier" },
      { id: "e-interrogation-orchestrator", source: "interrogation", target: "orchestrator", type: "bezier" },
      ...AGENTS.map((_, i) => ({
        id: `e-orchestrator-agent-${i}`,
        source: "orchestrator",
        target: `agent-${i}`,
        type: "bezier",
      })),
      ...AGENTS.map((_, i) => ({
        id: `e-agent-${i}-output`,
        source: `agent-${i}`,
        target: "output",
        type: "bezier",
      })),
    ],
    [],
  );

  return (
    <div className="w-full overflow-x-auto rounded-xl border border-line bg-paper-raised/40">
      <div style={{ width: 1150, height: 500 }}>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          fitView
          fitViewOptions={{ padding: 0.12 }}
          proOptions={{ hideAttribution: true }}
          nodesDraggable={false}
          nodesConnectable={false}
          elementsSelectable={false}
          panOnDrag={false}
          panOnScroll={false}
          zoomOnScroll={false}
          zoomOnPinch={false}
          zoomOnDoubleClick={false}
          defaultEdgeOptions={{ style: { stroke: "var(--color-line)", strokeWidth: 1.5 } }}
        >
          <Background variant={BackgroundVariant.Dots} gap={20} size={1} className="opacity-40" />
        </ReactFlow>
      </div>
    </div>
  );
}
