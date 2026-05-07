/*
 * Copyright 2026 4ward Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "p4c_backend/backend.h"

#include <array>
#include <fstream>
#include <string>
#include <utility>
#include <vector>

#include "frontends/p4/coreLibrary.h"
#include "frontends/p4/enumInstance.h"
#include "google/protobuf/text_format.h"
#include "lib/error.h"
#include "lib/log.h"

namespace P4::FourWard {

// =============================================================================
// Type emission
// =============================================================================

fourward::ir::Type FourWardBackend::emitType(const IR::Type* type) {
  fourward::ir::Type out;

  if (const auto* bits = type->to<IR::Type_Bits>()) {
    if (bits->isSigned) {
      out.mutable_signed_int()->set_width(bits->size);
    } else {
      out.mutable_bit()->set_width(bits->size);
    }
  } else if (const auto* vb = type->to<IR::Type_Varbits>()) {
    out.mutable_varbit()->set_max_width(vb->size);
  } else if (type->is<IR::Type_Boolean>()) {
    out.set_boolean(true);
  } else if (const auto* tn = type->to<IR::Type_Name>()) {
    // Resolve typedef aliases (e.g. `typedef bit<48> macAddr_t`) to their
    // underlying concrete types so the simulator sees bit widths instead of
    // opaque typedef names.
    const auto* decl = refMap_.getDeclaration(tn->path, false);
    if (const auto* td =
            decl != nullptr ? decl->to<IR::Type_Typedef>() : nullptr) {
      return emitType(td->type);
    }
    // P4's `type` keyword (Type_Newtype) creates a distinct named type, but
    // the simulator only needs the underlying bit width — resolve it here so
    // action params and struct fields get concrete types.
    if (const auto* nt =
            decl != nullptr ? decl->to<IR::Type_Newtype>() : nullptr) {
      return emitType(nt->type);
    }
    out.set_named(tn->path->name.name.c_str());
  } else if (const auto* hdr = type->to<IR::Type_Header>()) {
    out.set_named(hdr->name.name.c_str());
  } else if (const auto* st = type->to<IR::Type_Struct>()) {
    out.set_named(st->name.name.c_str());
  } else if (const auto* hu = type->to<IR::Type_HeaderUnion>()) {
    out.set_named(hu->name.name.c_str());
  } else if (const auto* stack = type->to<IR::Type_Array>()) {
    auto* hs = out.mutable_header_stack();
    if (const auto* elemType = stack->elementType->to<IR::Type_Name>()) {
      hs->set_element_type(elemType->path->name.name.c_str());
    }
    if (const auto* size = stack->size->to<IR::Constant>()) {
      hs->set_size(size->asInt());
    }
  } else if (type->is<IR::Type_Error>()) {
    out.set_error(true);
  } else if (const auto* ext = type->to<IR::Type_Extern>()) {
    // Extern object types (Hash, Meter, Register, etc.) — emit as named so the
    // simulator can identify the extern type in method call targets.
    out.set_named(ext->name.name.c_str());
  } else if (const auto* spec = type->to<IR::Type_SpecializedCanonical>()) {
    // Specialized generic extern (e.g. register<bit<8>>, direct_meter<bit<2>>).
    // Emit the base type name so the simulator can identify the extern.
    return emitType(spec->baseType);
  } else {
    // clang-format off
    LOG1("WARNING: unhandled type " << type->node_type_name() << "; emitting as unnamed");
    // clang-format on
  }
  return out;
}

// =============================================================================
// Expression emission
// =============================================================================

// Returns true if `expr` is a PathExpression referring to a P4Table, and if so
// sets `*tableName` to the table's original (pre-midend-rename) name.
static bool isTableApply(const IR::Expression* expr, const ReferenceMap& refMap,
                         std::string* tableName) {
  const auto* mc = expr->to<IR::MethodCallExpression>();
  if (mc == nullptr) return false;
  const auto* mem = mc->method->to<IR::Member>();
  if (mem == nullptr || mem->member != "apply") return false;
  const auto* pe = mem->expr->to<IR::PathExpression>();
  if (pe == nullptr) return false;
  const auto* decl = refMap.getDeclaration(pe->path);
  if (decl == nullptr || !decl->is<IR::P4Table>()) return false;
  if (tableName != nullptr)
    *tableName = decl->to<IR::P4Table>()->name.originalName.c_str();
  return true;
}

fourward::ir::Expr FourWardBackend::emitExpr(const IR::Expression* expr) {
  fourward::ir::Expr out;

  if (const auto* cnst = expr->to<IR::Constant>()) {
    auto* lit = out.mutable_literal();
    // Use big_integer for values that don't fit in 64 bits.
    if (cnst->fitsUint64()) {
      lit->set_integer(cnst->asUint64());
    } else {
      // Serialise as big-endian bytes.
      std::string bytes;
      auto v = cnst->value;
      while (v != 0) {
        bytes.push_back(static_cast<char>(static_cast<uint8_t>(v & 0xFF)));
        v >>= 8;
      }
      std::reverse(bytes.begin(), bytes.end());
      lit->set_big_integer(bytes);
    }
  } else if (const auto* b = expr->to<IR::BoolLiteral>()) {
    out.mutable_literal()->set_boolean(b->value);
  } else if (const auto* sl = expr->to<IR::StringLiteral>()) {
    out.mutable_literal()->set_string_literal(sl->value.c_str());
  } else if (const auto* pe = expr->to<IR::PathExpression>()) {
    out.mutable_name_ref()->set_name(pe->path->name.name.c_str());
  } else if (const auto* mem = expr->to<IR::Member>()) {
    std::string tableName;
    if (isTableApply(mem->expr, refMap_, &tableName)) {
      if (mem->member == "action_run") {
        // Switch subject: no type annotation needed.
        out.mutable_table_apply()->set_table_name(tableName);
        return out;
      }
      // hit/miss: type annotation (bool) is added by the common block below.
      auto* ta = out.mutable_table_apply();
      ta->set_table_name(tableName);
      if (mem->member == "hit" || mem->member == "miss") {
        ta->set_access_kind(mem->member == "hit"
                                ? fourward::ir::TableApplyExpr::HIT
                                : fourward::ir::TableApplyExpr::MISS);
      }
    } else if (mem->expr->is<IR::TypeNameExpression>()) {
      // Qualified enum/error member access: `error.NoError` or `MyEnum.Val`.
      // The base TypeNameExpression has no runtime value of its own; the whole
      // expression reduces to a compile-time literal.
      const auto* exprType = typeMap_.getType(expr);
      if (exprType != nullptr && exprType->is<IR::Type_Error>()) {
        // error.X → literal { error_member: "X" }
        out.mutable_literal()->set_error_member(mem->member.name.c_str());
      } else if (const auto* serEnum = exprType != nullptr
                                           ? exprType->to<IR::Type_SerEnum>()
                                           : nullptr) {
        // SerializableEnum.X → literal { integer: X.value }
        // The underlying integer value is stored in the SerEnumMember after
        // constant folding by the frontend.
        const auto* decl = serEnum->getDeclByName(mem->member.name);
        const auto* sem =
            decl != nullptr ? decl->to<IR::SerEnumMember>() : nullptr;
        const auto* cnst =
            sem != nullptr ? sem->value->to<IR::Constant>() : nullptr;
        if (cnst != nullptr) {
          auto* lit = out.mutable_literal();
          if (cnst->fitsUint64()) {
            lit->set_integer(cnst->asUint64());
          } else {
            std::string bytes;
            auto v = cnst->value;
            while (v != 0) {
              bytes.push_back(
                  static_cast<char>(static_cast<uint8_t>(v & 0xFF)));
              v >>= 8;
            }
            std::reverse(bytes.begin(), bytes.end());
            lit->set_big_integer(bytes);
          }
        } else {
          // clang-format off
          LOG1("WARNING: could not resolve SerEnum member value for " << mem->member.name);
          // clang-format on
        }
      } else {
        // Plain (non-serializable) enum member, e.g. HashAlgorithm.crc16.
        out.mutable_literal()->set_enum_member(mem->member.name.c_str());
      }
    } else {
      auto* fa = out.mutable_field_access();
      *fa->mutable_expr() = emitExpr(mem->expr);
      fa->set_field_name(mem->member.name.c_str());
    }
  } else if (const auto* slice = expr->to<IR::Slice>()) {
    auto* s = out.mutable_slice();
    *s->mutable_expr() = emitExpr(slice->e0);
    s->set_hi(slice->getH());
    s->set_lo(slice->getL());
  } else if (const auto* cat = expr->to<IR::Concat>()) {
    *out.mutable_concat()->mutable_left() = emitExpr(cat->left);
    *out.mutable_concat()->mutable_right() = emitExpr(cat->right);
  } else if (const auto* cast = expr->to<IR::Cast>()) {
    *out.mutable_cast()->mutable_target_type() = emitType(cast->destType);
    *out.mutable_cast()->mutable_expr() = emitExpr(cast->expr);
  } else if (const auto* ai = expr->to<IR::ArrayIndex>()) {
    // IR::ArrayIndex is a subclass of IR::Operation_Binary; check it first so
    // it maps to array_index rather than being caught as an unknown binary op.
    auto* a = out.mutable_array_index();
    *a->mutable_expr() = emitExpr(ai->left);
    *a->mutable_index() = emitExpr(ai->right);
  } else if (const auto* binop = expr->to<IR::Operation_Binary>()) {
    auto* b = out.mutable_binary_op();
    *b->mutable_left() = emitExpr(binop->left);
    *b->mutable_right() = emitExpr(binop->right);

    if (binop->is<IR::Add>())
      b->set_op(fourward::ir::BinaryOperator::ADD);
    else if (binop->is<IR::Sub>())
      b->set_op(fourward::ir::BinaryOperator::SUB);
    else if (binop->is<IR::Mul>())
      b->set_op(fourward::ir::BinaryOperator::MUL);
    else if (binop->is<IR::Div>())
      b->set_op(fourward::ir::BinaryOperator::DIV);
    else if (binop->is<IR::Mod>())
      b->set_op(fourward::ir::BinaryOperator::MOD);
    else if (binop->is<IR::AddSat>())
      b->set_op(fourward::ir::BinaryOperator::ADD_SAT);
    else if (binop->is<IR::SubSat>())
      b->set_op(fourward::ir::BinaryOperator::SUB_SAT);
    else if (binop->is<IR::BAnd>())
      b->set_op(fourward::ir::BinaryOperator::BIT_AND);
    else if (binop->is<IR::BOr>())
      b->set_op(fourward::ir::BinaryOperator::BIT_OR);
    else if (binop->is<IR::BXor>())
      b->set_op(fourward::ir::BinaryOperator::BIT_XOR);
    else if (binop->is<IR::Shl>())
      b->set_op(fourward::ir::BinaryOperator::SHL);
    else if (binop->is<IR::Shr>())
      b->set_op(fourward::ir::BinaryOperator::SHR);
    else if (binop->is<IR::Equ>())
      b->set_op(fourward::ir::BinaryOperator::EQ);
    else if (binop->is<IR::Neq>())
      b->set_op(fourward::ir::BinaryOperator::NEQ);
    else if (binop->is<IR::Lss>())
      b->set_op(fourward::ir::BinaryOperator::LT);
    else if (binop->is<IR::Grt>())
      b->set_op(fourward::ir::BinaryOperator::GT);
    else if (binop->is<IR::Leq>())
      b->set_op(fourward::ir::BinaryOperator::LE);
    else if (binop->is<IR::Geq>())
      b->set_op(fourward::ir::BinaryOperator::GE);
    else if (binop->is<IR::LAnd>())
      b->set_op(fourward::ir::BinaryOperator::AND);
    else if (binop->is<IR::LOr>())
      b->set_op(fourward::ir::BinaryOperator::OR);
    else {
      // clang-format off
      LOG1("WARNING: unhandled binary operator: " << binop->node_type_name());
      // clang-format on
    }
  } else if (const auto* unop = expr->to<IR::Operation_Unary>()) {
    auto* u = out.mutable_unary_op();
    *u->mutable_expr() = emitExpr(unop->expr);
    if (unop->is<IR::Neg>())
      u->set_op(fourward::ir::UnaryOperator::NEG);
    else if (unop->is<IR::Cmpl>())
      u->set_op(fourward::ir::UnaryOperator::BIT_NOT);
    else if (unop->is<IR::LNot>())
      u->set_op(fourward::ir::UnaryOperator::NOT);
    else {
      // clang-format off
      LOG1("WARNING: unhandled unary operator: " << unop->node_type_name());
      // clang-format on
    }
  } else if (const auto* mux = expr->to<IR::Mux>()) {
    auto* m = out.mutable_mux();
    *m->mutable_condition() = emitExpr(mux->e0);
    *m->mutable_then_expr() = emitExpr(mux->e1);
    *m->mutable_else_expr() = emitExpr(mux->e2);
  } else if (const auto* mc = expr->to<IR::MethodCallExpression>()) {
    // Special case: table.apply() — emit as TableApplyExpr.
    std::string tableName;
    if (isTableApply(expr, refMap_, &tableName)) {
      out.mutable_table_apply()->set_table_name(tableName);
      return out;  // no type annotation for TableApplyExpr
    }

    auto* call = out.mutable_method_call();
    // The method is typically a Member expression: target.method
    if (const auto* mem = mc->method->to<IR::Member>()) {
      *call->mutable_target() = emitExpr(mem->expr);
      call->set_method(mem->member.name.c_str());
    } else {
      *call->mutable_target() = emitExpr(mc->method);
      call->set_method("__call__");
    }
    for (const auto* arg : *mc->arguments) {
      *call->add_args() = emitExpr(arg->expression);
    }
  } else if (const auto* se = expr->to<IR::StructExpression>()) {
    auto* s = out.mutable_struct_expr();
    for (const auto* comp : se->components) {
      auto* field = s->add_fields();
      field->set_name(comp->name.name.c_str());
      *field->mutable_value() = emitExpr(comp->expression);
    }
  } else {
    // clang-format off
    LOG1("WARNING: unhandled expression " << expr->node_type_name());
    // clang-format on
  }

  // Always populate the type annotation.
  if (const auto* type = typeMap_.getType(expr)) {
    *out.mutable_type() = emitType(type);
  }

  return out;
}

// =============================================================================
// Source location
// =============================================================================

fourward::ir::SourceInfo FourWardBackend::emitSourceInfo(const IR::Node* node) {
  fourward::ir::SourceInfo out;
  auto si = node->getSourceInfo();
  if (si.isValid()) {
    out.set_file(si.getSourceFile().c_str());
    out.set_line(si.toPosition().sourceLine);
    out.set_column(si.getStart().getColumnNumber());
  }
  out.set_source_fragment(node->toString().c_str());
  return out;
}

// =============================================================================
// Statement emission
// =============================================================================

fourward::ir::Stmt FourWardBackend::emitStmt(const IR::StatOrDecl* node) {
  fourward::ir::Stmt out;

  if (const auto* assign = node->to<IR::AssignmentStatement>()) {
    auto* a = out.mutable_assignment();
    *a->mutable_lhs() = emitExpr(assign->left);
    *a->mutable_rhs() = emitExpr(assign->right);
  } else if (const auto* mc = node->to<IR::MethodCallStatement>()) {
    *out.mutable_method_call()->mutable_call() = emitExpr(mc->methodCall);
  } else if (const auto* ifst = node->to<IR::IfStatement>()) {
    auto* i = out.mutable_if_stmt();
    *i->mutable_condition() = emitExpr(ifst->condition);
    // SimplifyControlFlow normally wraps branches in BlockStatements, but some
    // downstream passes (e.g. LocalCopyPropagation) may produce bare
    // statements.
    auto emitBranch =
        [&](const IR::Statement* stmt) -> fourward::ir::BlockStmt {
      if (const auto* blk = stmt->to<IR::BlockStatement>())
        return emitBlock(blk);
      fourward::ir::BlockStmt branch;
      // IR::EmptyStatement (produced by RemoveReturns for void-return branches)
      // has no IR representation; skip it to avoid an empty Stmt{} in the
      // output.
      if (!stmt->is<IR::EmptyStatement>()) *branch.add_stmts() = emitStmt(stmt);
      return branch;
    };
    *i->mutable_then_block() = emitBranch(ifst->ifTrue);
    if (ifst->ifFalse != nullptr) {
      *i->mutable_else_block() = emitBranch(ifst->ifFalse);
    }
  } else if (const auto* sw = node->to<IR::SwitchStatement>()) {
    // Detect value-based vs action_run switches: if any non-default case label
    // is not a PathExpression, this is a value switch on a scalar expression.
    bool isValueSwitch = false;
    for (const auto* c : sw->cases) {
      if (!c->label->is<IR::DefaultExpression>() &&
          !c->label->is<IR::PathExpression>()) {
        isValueSwitch = true;
        break;
      }
    }

    if (isValueSwitch) {
      // Emit value-based switch as an if-else chain:
      //   if (subject == case1) { ... } else if (subject == case2) { ... } else
      //   { default }
      fourward::ir::Stmt* cursor = &out;
      for (const auto* c : sw->cases) {
        if (c->label->is<IR::DefaultExpression>()) continue;
        auto* ifStmt = cursor->mutable_if_stmt();
        // condition: subject == case_value
        auto* cond = ifStmt->mutable_condition()->mutable_binary_op();
        cond->set_op(fourward::ir::BinaryOperator::EQ);
        *cond->mutable_left() = emitExpr(sw->expression);
        *cond->mutable_right() = emitExpr(c->label);
        if (c->statement != nullptr) {
          if (const auto* b = c->statement->to<IR::BlockStatement>()) {
            *ifStmt->mutable_then_block() = emitBlock(b);
          }
        }
        // Chain: set cursor to the else branch for the next case.
        cursor = ifStmt->mutable_else_block()->add_stmts();
      }
      // Emit default case as the final else block.
      for (const auto* c : sw->cases) {
        if (c->label->is<IR::DefaultExpression>() && c->statement != nullptr) {
          if (const auto* b = c->statement->to<IR::BlockStatement>()) {
            // cursor points to an empty stmt in the last else block; replace
            // the surrounding block with the default's statements.
            auto* parent = cursor->mutable_block();
            for (const auto* s : b->components) {
              *parent->add_stmts() = emitStmt(s);
            }
          }
        }
      }
    } else {
      // Action_run switch on a table apply result.
      auto* s = out.mutable_switch_stmt();
      *s->mutable_subject() = emitExpr(sw->expression);
      for (const auto* c : sw->cases) {
        if (c->label->is<IR::DefaultExpression>()) {
          if (const auto* b = c->statement->to<IR::BlockStatement>()) {
            *s->mutable_default_block() = emitBlock(b);
          }
        } else {
          auto* sc = s->add_cases();
          if (const auto* pe = c->label->to<IR::PathExpression>()) {
            // Use the original (pre-rename) action name to match p4info
            // aliases.
            sc->set_action_name(pe->path->name.originalName.c_str());
          }
          if (c->statement != nullptr) {
            if (const auto* b = c->statement->to<IR::BlockStatement>()) {
              *sc->mutable_block() = emitBlock(b);
            }
          }
        }
      }
    }
  } else if (const auto* blk = node->to<IR::BlockStatement>()) {
    *out.mutable_block() = emitBlock(blk);
  } else if (node->is<IR::ExitStatement>()) {
    out.mutable_exit();
  } else if (const auto* ret = node->to<IR::ReturnStatement>()) {
    if (ret->expression != nullptr) {
      *out.mutable_return_stmt()->mutable_value() = emitExpr(ret->expression);
    } else {
      out.mutable_return_stmt();
    }
  } else {
    // clang-format off
    LOG1("WARNING: unhandled statement " << node->node_type_name());
    // clang-format on
  }
  // For if-statements, use the condition as the source fragment (e.g.
  // "hdr.ipv4.isValid()") — the statement's own toString() is just
  // "IfStatement" which isn't helpful in trace output.
  if (const auto* ifst = node->to<IR::IfStatement>()) {
    *out.mutable_source_info() = emitSourceInfo(ifst->condition);
  } else {
    *out.mutable_source_info() = emitSourceInfo(node);
  }
  return out;
}

fourward::ir::BlockStmt FourWardBackend::emitBlock(
    const IR::BlockStatement* block) {
  fourward::ir::BlockStmt out;
  for (const auto* stmt : block->components) {
    *out.add_stmts() = emitStmt(stmt);
  }
  return out;
}

// =============================================================================
// FourWardBackend
// =============================================================================

FourWardBackend::FourWardBackend(const FourWardOptions& options,
                                 const ReferenceMap& refMap,
                                 const TypeMap& typeMap)
    : options_(options), refMap_(refMap), typeMap_(typeMap) {
  behavioral_ = pipelineConfig_.mutable_device()->mutable_behavioral();
}

void FourWardBackend::process(const IR::ToplevelBlock* toplevel) {
  const auto* program = toplevel->getProgram();

  emitTypeDecls(program);
  emitArchitecture(toplevel);

  for (const auto* decl : *program->getDeclarations()) {
    if (const auto* parser = decl->to<IR::P4Parser>()) {
      emitParser(parser);
    } else if (const auto* control = decl->to<IR::P4Control>()) {
      emitControl(control);
    }
  }
}

void FourWardBackend::setP4Info(p4::config::v1::P4Info p4info) {
  *pipelineConfig_.mutable_p4info() = std::move(p4info);
}

void FourWardBackend::setStaticEntries(p4::v1::WriteRequest entries) {
  *pipelineConfig_.mutable_device()->mutable_static_entries() =
      std::move(entries);
}

void FourWardBackend::setTypeTranslations(
    std::vector<fourward::ir::TypeTranslation> translations) {
  auto* device = pipelineConfig_.mutable_device();
  for (auto& t : translations) {
    *device->add_translations() = std::move(t);
  }
}

void FourWardBackend::setPortTypeName(std::string portTypeName) {
  behavioral_->mutable_architecture()->set_port_type_name(
      std::move(portTypeName));
}

void FourWardBackend::emitTypeDecls(const IR::P4Program* program) {
  for (const auto* decl : *program->getDeclarations()) {
    if (const auto* hdr = decl->to<IR::Type_Header>()) {
      auto* td = behavioral_->add_types();
      td->set_name(hdr->name.name.c_str());
      auto* hdecl = td->mutable_header();
      for (const auto* field : hdr->fields) {
        auto* fd = hdecl->add_fields();
        fd->set_name(field->name.name.c_str());
        *fd->mutable_type() = emitType(field->type);
      }
    } else if (const auto* st = decl->to<IR::Type_Struct>()) {
      auto* td = behavioral_->add_types();
      td->set_name(st->name.name.c_str());
      auto* sdecl = td->mutable_struct_();
      for (const auto* field : st->fields) {
        auto* fd = sdecl->add_fields();
        fd->set_name(field->name.name.c_str());
        *fd->mutable_type() = emitType(field->type);
        // Emit @field_list annotations for metadata preservation across
        // clone/resubmit/recirculate.
        for (const auto* ann : field->annotations) {
          if (ann->name.name == "field_list") {
            for (const auto* arg : ann->getExpr()) {
              if (const auto* c = arg->to<IR::Constant>()) {
                fd->add_field_list_ids(c->asInt());
              }
            }
          }
        }
      }
    } else if (const auto* serEnum = decl->to<IR::Type_SerEnum>()) {
      // Serializable enums (enum bit<N> E { ... }) can appear as header field
      // types; emit their underlying bit width so the simulator can compute
      // wire offsets.
      auto* td = behavioral_->add_types();
      td->set_name(serEnum->name.name.c_str());
      auto* edecl = td->mutable_enum_();
      if (const auto* bits = serEnum->type->to<IR::Type_Bits>()) {
        edecl->set_width(bits->size);
      }
      for (const auto* member : serEnum->members) {
        edecl->add_members(member->name.name.c_str());
      }
    } else if (const auto* hu = decl->to<IR::Type_HeaderUnion>()) {
      auto* td = behavioral_->add_types();
      td->set_name(hu->name.name.c_str());
      auto* udecl = td->mutable_header_union();
      for (const auto* field : hu->fields) {
        auto* fd = udecl->add_fields();
        fd->set_name(field->name.name.c_str());
        *fd->mutable_type() = emitType(field->type);
      }
    }
  }
}

void FourWardBackend::emitParser(const IR::P4Parser* parser) {
  auto* pd = behavioral_->add_parsers();
  pd->set_name(parser->name.name.c_str());

  for (const auto* param : parser->getApplyParameters()->parameters) {
    auto* p = pd->add_params();
    p->set_name(param->name.name.c_str());
    *p->mutable_type() = emitType(param->type);
    switch (param->direction) {
      case IR::Direction::In:
        p->set_direction(fourward::ir::Direction::IN);
        break;
      case IR::Direction::Out:
        p->set_direction(fourward::ir::Direction::OUT);
        break;
      case IR::Direction::InOut:
        p->set_direction(fourward::ir::Direction::INOUT);
        break;
      default:
        break;
    }
  }

  // Emit parser-local variables and extern instances.
  for (const auto* decl : parser->parserLocals) {
    if (const auto* varDecl = decl->to<IR::Declaration_Variable>()) {
      auto* vd = pd->add_local_vars();
      vd->set_name(varDecl->name.name.c_str());
      *vd->mutable_type() = emitType(varDecl->type);
      if (varDecl->initializer != nullptr) {
        *vd->mutable_initializer() = emitExpr(varDecl->initializer);
      }
    } else if (const auto* inst = decl->to<IR::Declaration_Instance>()) {
      auto* ei = pd->add_extern_instances();
      ei->set_name(inst->name.name.c_str());
      if (const auto* tn = inst->type->to<IR::Type_Name>()) {
        ei->set_type_name(tn->path->name.name.c_str());
      } else if (const auto* spec = inst->type->to<IR::Type_Specialized>()) {
        if (const auto* base = spec->baseType->to<IR::Type_Name>()) {
          ei->set_type_name(base->path->name.name.c_str());
        }
      }
      for (const auto* arg : *inst->arguments) {
        *ei->add_constructor_args() = emitExpr(arg->expression);
      }
    } else if (const auto* pvs = decl->to<IR::P4ValueSet>()) {
      auto* vsd = pd->add_value_sets();
      vsd->set_name(pvs->name.name.c_str());
      if (const auto* sz = pvs->size->to<IR::Constant>()) {
        vsd->set_size(sz->asUnsigned());
      }
    }
  }

  for (const auto* state : parser->states) {
    auto* ps = pd->add_states();
    ps->set_name(state->name.name.c_str());
    *ps->mutable_source_info() = emitSourceInfo(state);

    for (const auto* stmt : state->components) {
      *ps->add_stmts() = emitStmt(stmt);
    }

    // accept/reject are terminal states with no selectExpression.
    if (state->selectExpression == nullptr) {
    } else if (const auto* sel =
                   state->selectExpression->to<IR::SelectExpression>()) {
      auto* selectTrans = ps->mutable_transition()->mutable_select();
      for (const auto* key : sel->select->components) {
        *selectTrans->add_keys() = emitExpr(key);
      }
      // Helper: emit a single keyset expression into a KeysetExpr proto.
      auto emitKeyset = [this](fourward::ir::KeysetExpr* k,
                               const IR::Expression* expr) {
        if (expr->is<IR::DefaultExpression>()) {
          k->set_default_case(true);
        } else if (const auto* range = expr->to<IR::Range>()) {
          auto* r = k->mutable_range();
          *r->mutable_lo() = emitExpr(range->left);
          *r->mutable_hi() = emitExpr(range->right);
        } else if (const auto* mask = expr->to<IR::Mask>()) {
          auto* m = k->mutable_mask();
          *m->mutable_value() = emitExpr(mask->left);
          *m->mutable_mask() = emitExpr(mask->right);
        } else if (const auto* pe = expr->to<IR::PathExpression>()) {
          // P4 spec §12.14: a PathExpression in a select keyset may refer to a
          // parser value_set rather than a compile-time constant.
          const auto* decl = refMap_.getDeclaration(pe->path, false);
          if (decl != nullptr && decl->is<IR::P4ValueSet>()) {
            k->set_value_set(pe->path->name.name.c_str());
          } else {
            *k->mutable_exact() = emitExpr(expr);
          }
        } else {
          *k->mutable_exact() = emitExpr(expr);
        }
      };

      for (const auto* sc : sel->selectCases) {
        if (sc->keyset->is<IR::DefaultExpression>()) {
          selectTrans->set_default_state(sc->state->path->name.name.c_str());
          continue;
        }
        auto* c = selectTrans->add_cases();
        // Multi-key selects use ListExpression; single-key selects have a
        // scalar expression. Emit one KeysetExpr per key.
        if (const auto* list = sc->keyset->to<IR::ListExpression>()) {
          for (const auto* comp : list->components) {
            emitKeyset(c->add_keysets(), comp);
          }
        } else {
          emitKeyset(c->add_keysets(), sc->keyset);
        }
        c->set_next_state(sc->state->path->name.name.c_str());
      }
    } else if (const auto* path =
                   state->selectExpression->to<IR::PathExpression>()) {
      ps->mutable_transition()->set_next_state(path->path->name.name.c_str());
    }
  }
}

void FourWardBackend::emitControl(const IR::P4Control* control) {
  controlName_ = control->name.name.c_str();

  auto* cd = behavioral_->add_controls();
  cd->set_name(controlName_);

  for (const auto* param : control->getApplyParameters()->parameters) {
    auto* p = cd->add_params();
    p->set_name(param->name.name.c_str());
    *p->mutable_type() = emitType(param->type);
    switch (param->direction) {
      case IR::Direction::In:
        p->set_direction(fourward::ir::Direction::IN);
        break;
      case IR::Direction::Out:
        p->set_direction(fourward::ir::Direction::OUT);
        break;
      case IR::Direction::InOut:
        p->set_direction(fourward::ir::Direction::INOUT);
        break;
      default:
        break;
    }
  }

  for (const auto* decl : control->controlLocals) {
    if (const auto* action = decl->to<IR::P4Action>()) {
      auto* ad = cd->add_local_actions();
      emitAction(action, ad);
    } else if (const auto* table = decl->to<IR::P4Table>()) {
      emitTable(table);
    } else if (const auto* varDecl = decl->to<IR::Declaration_Variable>()) {
      auto* vd = cd->add_local_vars();
      vd->set_name(varDecl->name.name.c_str());
      *vd->mutable_type() = emitType(varDecl->type);
      if (varDecl->initializer != nullptr) {
        *vd->mutable_initializer() = emitExpr(varDecl->initializer);
      }
    } else if (const auto* inst = decl->to<IR::Declaration_Instance>()) {
      // Extern object instances (Hash, Meter, Register, etc.) with their
      // constructor argument values — needed by the simulator to implement
      // architecture-specific semantics (e.g. hash algorithm selection).
      auto* ei = cd->add_extern_instances();
      ei->set_name(inst->name.name.c_str());
      // Extract the base extern type name from the (possibly specialized) type.
      if (const auto* tn = inst->type->to<IR::Type_Name>()) {
        ei->set_type_name(tn->path->name.name.c_str());
      } else if (const auto* spec = inst->type->to<IR::Type_Specialized>()) {
        if (const auto* base = spec->baseType->to<IR::Type_Name>()) {
          ei->set_type_name(base->path->name.name.c_str());
        }
      }
      for (const auto* arg : *inst->arguments) {
        *ei->add_constructor_args() = emitExpr(arg->expression);
      }
    }
  }

  for (const auto* stmt : control->body->components) {
    *cd->add_apply_body() = emitStmt(stmt);
  }
}

void FourWardBackend::emitAction(const IR::P4Action* action,
                                 fourward::ir::ActionDecl* out) {
  // Use the original (pre-midend) name as the canonical key so it matches the
  // p4info alias and allows table-dispatch lookups to succeed.
  out->set_name(action->name.originalName.c_str());
  // If the midend renamed this action (e.g. "do_thing" → "do_thing_1"), also
  // record the current name so the interpreter can resolve direct call sites
  // that use it.
  if (action->name.name != action->name.originalName) {
    out->set_current_name(action->name.name.c_str());
  }
  for (const auto* param : action->parameters->parameters) {
    auto* p = out->add_params();
    p->set_name(param->name.name.c_str());
    *p->mutable_type() = emitType(param->type);
  }
  for (const auto* stmt : action->body->components) {
    *out->add_body() = emitStmt(stmt);
  }
}

void FourWardBackend::emitTable(const IR::P4Table* table) {
  // originalName matches the p4info alias (e.g. "port_table" from
  // "MyIngress.port_table").
  const std::string tableName = table->name.originalName.c_str();

  // Look up the p4info table by qualified name to retrieve match field IDs.
  // Try multiple strategies: (1) controlName.originalName for simple tables,
  // (2) externalName() for tables from inlined sub-controls (where p4info uses
  // dot-separated hierarchy like "ingress.c.t" but originalName has underscores
  // like "c_t").
  const p4::config::v1::Table* p4Table = nullptr;
  {
    std::array<std::string, 2> candidates = {
        controlName_ + "." + tableName,
        std::string(table->externalName().c_str()),
    };
    // externalName() may have a leading dot for fully-qualified names.
    for (auto& c : candidates) {
      if (!c.empty() && c[0] == '.') c = c.substr(1);
    }
    for (const auto& t : pipelineConfig_.p4info().tables()) {
      for (const auto& c : candidates) {
        if (t.preamble().name() == c) {
          p4Table = &t;
          break;
        }
      }
      if (p4Table != nullptr) break;
    }
  }
  if (p4Table == nullptr) {
    // clang-format off
    LOG1("WARNING: no p4info table found for " << tableName << "; skipping emitTable");
    // clang-format on
    return;
  }

  auto* tb = behavioral_->add_tables();
  tb->set_name(tableName);

  // Emit one TableKey per match field. The field_name is the p4info match
  // field ID as a string; this is what TableStore.lookup compares against
  // FieldMatch.fieldId from P4Runtime write requests.
  const IR::Key* key = table->getKey();
  if (key == nullptr) return;
  int keyIdx = 0;
  for (const auto* keyElem : key->keyElements) {
    if (keyIdx >= p4Table->match_fields_size()) break;
    auto* tk = tb->add_keys();
    tk->set_field_name(std::to_string(p4Table->match_fields(keyIdx).id()));
    *tk->mutable_expr() = emitExpr(keyElem->expression);
    ++keyIdx;
  }

  // Record per-table action specializations so the interpreter can resolve
  // the correct midend copy when the p4info returns a single original name.
  const auto* actionList = table->getActionList();
  if (actionList != nullptr) {
    for (const auto* ale : actionList->actionList) {
      auto id = ale->getName();
      if (id.name != id.originalName) {
        (*tb->mutable_action_overrides())[id.originalName.c_str()] =
            id.name.c_str();
      }
    }
  }
}

void FourWardBackend::emitArchitecture(const IR::ToplevelBlock* toplevel) {
  auto* arch = behavioral_->mutable_architecture();

  const auto* main = toplevel->getMain();
  if (main == nullptr) return;

  auto addStage = [&](const std::string& name, const std::string& blockName,
                      fourward::ir::StageKind kind) {
    auto* stage = arch->add_stages();
    stage->set_name(name);
    stage->set_block_name(blockName);
    stage->set_kind(kind);
  };

  // Resolves a constructor-call expression to the block name it creates.
  auto resolveBlockName = [](const IR::Expression* expr) -> std::string {
    if (const auto* pe = expr->to<IR::PathExpression>()) {
      return pe->path->name.name.c_str();
    }
    if (const auto* cce = expr->to<IR::ConstructorCallExpression>()) {
      if (const auto* tn = cce->constructedType->to<IR::Type_Name>()) {
        return tn->path->name.name.c_str();
      }
    }
    if (const auto* mc = expr->to<IR::MethodCallExpression>()) {
      if (const auto* pe = mc->method->to<IR::PathExpression>()) {
        return pe->path->name.name.c_str();
      }
    }
    return "";
  };

  if (main->type->name == "V1Switch") {
    arch->set_name("v1model");

    // V1Switch(parser, verify_checksum, ingress, egress, compute_checksum,
    // deparser)
    const std::vector<std::pair<std::string, fourward::ir::StageKind>>
        stageSpec = {
            {"parser", fourward::ir::StageKind::PARSER},
            {"verify_checksum", fourward::ir::StageKind::CONTROL},
            {"ingress", fourward::ir::StageKind::CONTROL},
            {"egress", fourward::ir::StageKind::CONTROL},
            {"compute_checksum", fourward::ir::StageKind::CONTROL},
            {"deparser", fourward::ir::StageKind::DEPARSER},
        };

    size_t i = 0;
    for (const auto& arg :
         *main->node->to<IR::Declaration_Instance>()->arguments) {
      if (i >= stageSpec.size()) break;
      std::string blockName = resolveBlockName(arg->expression);
      if (!blockName.empty()) {
        addStage(stageSpec[i].first, blockName, stageSpec[i].second);
      }
      ++i;
    }
  } else if (main->type->name == "PSA_Switch") {
    arch->set_name("psa");

    // PSA_Switch(IngressPipeline ingress, PRE pre, EgressPipeline egress,
    //            BufferingQueueingEngine bqe)
    // IngressPipeline(IngressParser ip, Ingress ig, IngressDeparser id)
    // EgressPipeline(EgressParser ep, Egress eg, EgressDeparser ed)
    //
    // Pipeline args are package references. Resolve them via refMap_ to their
    // Declaration_Instance nodes and extract block names from their constructor
    // arguments.
    auto pipelineArgs =
        [&](const IR::Expression* ref) -> const IR::Vector<IR::Argument>* {
      const auto* pe = ref->to<IR::PathExpression>();
      if (pe == nullptr) return nullptr;
      const auto* decl = refMap_.getDeclaration(pe->path, false);
      if (decl == nullptr) return nullptr;
      const auto* inst = decl->to<IR::Declaration_Instance>();
      return inst != nullptr ? inst->arguments : nullptr;
    };

    const auto* mainArgs =
        main->node->to<IR::Declaration_Instance>()->arguments;
    if (mainArgs->size() < 4) {
      ::P4::error("PSA_Switch: expected 4 constructor arguments, got %1%",
                  mainArgs->size());
      return;
    }

    const auto* ingressArgs = pipelineArgs((*mainArgs)[0]->expression);
    const auto* egressArgs = pipelineArgs((*mainArgs)[2]->expression);
    if (ingressArgs == nullptr || ingressArgs->size() < 3 ||
        egressArgs == nullptr || egressArgs->size() < 3) {
      ::P4::error(
          "PSA_Switch: could not resolve IngressPipeline or EgressPipeline "
          "constructor arguments");
      return;
    }

    // IngressPipeline(IngressParser ip, Ingress ig, IngressDeparser id)
    addStage("ingress_parser", resolveBlockName((*ingressArgs)[0]->expression),
             fourward::ir::StageKind::PARSER);
    addStage("ingress", resolveBlockName((*ingressArgs)[1]->expression),
             fourward::ir::StageKind::CONTROL);
    addStage("ingress_deparser",
             resolveBlockName((*ingressArgs)[2]->expression),
             fourward::ir::StageKind::DEPARSER);

    // EgressPipeline(EgressParser ep, Egress eg, EgressDeparser ed)
    addStage("egress_parser", resolveBlockName((*egressArgs)[0]->expression),
             fourward::ir::StageKind::PARSER);
    addStage("egress", resolveBlockName((*egressArgs)[1]->expression),
             fourward::ir::StageKind::CONTROL);
    addStage("egress_deparser", resolveBlockName((*egressArgs)[2]->expression),
             fourward::ir::StageKind::DEPARSER);
  } else if (main->type->name == "PNA_NIC") {
    arch->set_name("pna");

    // PNA_NIC(main_parser, pre_control, main_control, main_deparser)
    const std::vector<std::pair<std::string, fourward::ir::StageKind>>
        stageSpec = {
            {"main_parser", fourward::ir::StageKind::PARSER},
            {"pre_control", fourward::ir::StageKind::CONTROL},
            {"main_control", fourward::ir::StageKind::CONTROL},
            {"main_deparser", fourward::ir::StageKind::DEPARSER},
        };

    size_t i = 0;
    for (const auto& arg :
         *main->node->to<IR::Declaration_Instance>()->arguments) {
      if (i >= stageSpec.size()) break;
      std::string blockName = resolveBlockName(arg->expression);
      if (!blockName.empty()) {
        addStage(stageSpec[i].first, blockName, stageSpec[i].second);
      }
      ++i;
    }
  } else {
    // Unknown architecture: emit the name and leave stages empty.
    // The simulator will reject it with a clear error.
    arch->set_name(main->type->name.name.c_str());
    ::P4::error(
        "4ward: unsupported architecture '%1%'. Only v1model, PSA, and PNA are "
        "supported currently.",
        main->type->name);
  }
}

namespace {

// 4ward standardises on .txtpb / .binpb for proto files. `validateOutputs`
// in main.cpp rejects anything else right after argument parsing, so by the
// time we reach `writeProto` the suffix is guaranteed to be one of the two.
bool isBinary(const std::string& path) { return path.ends_with(".binpb"); }

bool writeProto(const google::protobuf::Message& msg, const std::string& path,
                const char* protoFile, const char* protoMessage) {
  // Serialise into a string in full, then write the bytes all at once. Avoids
  // a subtle silent-truncation bug (issue #592): SerializeToOstream only
  // checks `output->good()` after its internal `OstreamOutputStream` flushes
  // into the std::ofstream buffer. The std::ofstream's own destructor flush
  // to disk happens after this function returns, and a failure there sets
  // badbit silently — no exception, no error propagation. Manifested in
  // google3 as 201-byte truncated files for ~130 KB DeviceConfigs.
  std::string serialised;
  if (isBinary(path)) {
    if (!msg.SerializeToString(&serialised)) {
      ::P4::error("4ward: failed to serialise %1% to '%2%'", protoMessage,
                  path);
      return false;
    }
  } else {
    std::string text;
    if (!google::protobuf::TextFormat::PrintToString(msg, &text)) {
      ::P4::error("4ward: failed to serialise %1% to '%2%'", protoMessage,
                  path);
      return false;
    }
    serialised = "# proto-file: ";
    serialised += protoFile;
    serialised += "\n# proto-message: ";
    serialised += protoMessage;
    serialised += "\n\n";
    serialised += text;
  }

  std::ofstream out(path, std::ios::binary);
  if (!out.is_open()) {
    ::P4::error("4ward: cannot open output file '%1%'", path);
    return false;
  }
  out.write(serialised.data(), static_cast<std::streamsize>(serialised.size()));
  out.close();
  if (out.fail()) {
    ::P4::error("4ward: failed to write %1% to '%2%' (close/flush failed)",
                protoMessage, path);
    return false;
  }
  // clang-format off
  LOG1("4ward: wrote " << path);
  // clang-format on
  return true;
}

}  // namespace

bool FourWardBackend::writeOutputs() const {
  if (options_.outputFile.has_value()) {
    if (options_.format == FourWardOptions::Format::kP4runtime) {
      p4::v1::ForwardingPipelineConfig fpc;
      *fpc.mutable_p4info() = pipelineConfig_.p4info();
      fpc.set_p4_device_config(pipelineConfig_.device().SerializeAsString());
      if (!writeProto(fpc, *options_.outputFile,
                      "@p4runtime//p4/v1/p4runtime.proto",
                      "p4.v1.ForwardingPipelineConfig")) {
        return false;
      }
    } else if (!writeProto(pipelineConfig_, *options_.outputFile,
                           "@fourward//simulator/ir.proto",
                           "fourward.ir.PipelineConfig")) {
      return false;
    }
  }
  if (options_.outP4Info.has_value() &&
      !writeProto(pipelineConfig_.p4info(), *options_.outP4Info,
                  "@p4runtime//p4/config/v1/p4info.proto",
                  "p4.config.v1.P4Info")) {
    return false;
  }
  if (options_.outP4DeviceConfig.has_value() &&
      !writeProto(pipelineConfig_.device(), *options_.outP4DeviceConfig,
                  "@fourward//simulator/ir.proto",
                  "fourward.ir.DeviceConfig")) {
    return false;
  }
  return true;
}

}  // namespace P4::FourWard
